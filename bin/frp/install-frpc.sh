#!/bin/bash
# ============================================================================
# FRP 客户端（frpc）安装 —— Docker 容器方式，在本机 Mac 执行
#
# 架构：盘家系统部署在每台成员自己的机器上（docker 容器），frpc 也容器化，
#       全部连到你服务器的 frps，外网通过 http://panjia.icu:端口 访问各自系统。
#       frpc 容器内通过 host.docker.internal 访问宿主机上映射出来的 ui/api 端口。
#
# 两种模式：
#   1) 本机安装（默认）：构建 panjia-frpc 镜像并启动常驻容器
#        sh bin/frp/install-frpc.sh [--reset-config]
#   2) 生成成员配置（--gen-user）：管理员生成「成员 toml + 镜像 tar」包供分发，
#      成员机器上只要有 docker，load + run 两条命令接入
#        REMOTE_WEB_PORT=3001 REMOTE_API_PORT=8081 \
#            sh bin/frp/install-frpc.sh --gen-user zhangsan
#
# 端口分配约定（第 N 号成员，N 从 0 开始，每人一组绝不重复）：
#   ui  远端端口 = 3000 + N     （0 号：3000，1 号：3001，… 上限 3099）
#   api 远端端口 = 8080 + N     （0 号：8080，1 号：8081，… 上限 8099）
#   本机服务端口所有成员相同：panjia-ui 80 / panjia-server 8080（IDE/dev 默认）
#   日常使用只需 ui 端口（panjia-ui 的 vite proxy 已把 /dev-api 反代到 panjia-server），
#   api 端口用于调试/对接。
#
# 参数：
#   --reset-config          重新拉取 token 并重写本机 frpc.toml
#   --gen-user <名字>       生成成员分发包（不装本机容器）；必须用环境变量
#                           显式指定该成员的 REMOTE_WEB_PORT / REMOTE_API_PORT
#   端口环境变量：
#     LOCAL_WEB_PORT=80 LOCAL_API_PORT=8080      本机服务端口（panjia-ui / panjia-server）
#     REMOTE_WEB_PORT / REMOTE_API_PORT          服务器暴露端口（每人不同！）
#
# 幂等性：可重复执行
#   - 镜像已存在 → [SKIP] 构建
#   - frpc.toml 已存在 → 保留（token 不变），--reset-config 才重建
#   - 容器：rm -f 旧容器后以最新配置重建（--restart unless-stopped 常驻）
#
# 本机落点：
#   bin/frp/frpc.toml       本机配置（含 token，不入 Git）
#   bin/frp/dist/           成员分发包：frpc-<名字>.toml + panjia-frpc tar（不入 Git）
#   镜像/容器：panjia-frpc:<版本> / panjia-frpc
#
# 前置条件：
#   1. 本机 docker 可用（成员机器同样只需 docker）
#   2. 服务器已安装 frps：sh bin/frp/install-frps.sh
#   3. 两种模式都由管理员本机执行（SSH 到服务器 sudo 读 token 写进配置）；
#      成员侧只需拿到分发包，无需任何权限
# ============================================================================

set -e

FRP_VERSION="0.71.0"   # frp 版本（升级改这里，与 install-frps.sh / pack-frpc.sh 保持一致）
IMAGE_NAME="panjia-frpc"
IMAGE_TAG="${FRP_VERSION}"
CONTAINER_NAME="panjia-frpc"

RESET_CONFIG=0
GEN_USER=""
while [ $# -gt 0 ]; do
    case "$1" in
        --reset-config) RESET_CONFIG=1; shift ;;
        --gen-user)
            GEN_USER="${2:-}"
            [ -n "${GEN_USER}" ] || { echo "[ERROR] --gen-user 后需要成员名（如 zhangsan）"; exit 1; }
            shift 2 ;;
        --help|-h)
            awk 'NR==1{next} /^#/{sub(/^# ?/,""); print; next} {exit}' "$0"
            exit 0 ;;
        *) echo "[ERROR] 未知参数：$1"; exit 1 ;;
    esac
done

FRP_DIR="$(cd "$(dirname "$0")" && pwd)"
FRPC_CONF="${FRP_DIR}/frpc.toml"
DIST_DIR="${FRP_DIR}/dist"

# ---- 端口（gen 模式要求远端端口显式指定，防止所有人撞默认端口）----
GEN_WEB_SPECIFIED="${REMOTE_WEB_PORT:-}"
GEN_API_SPECIFIED="${REMOTE_API_PORT:-}"
LOCAL_WEB_PORT="${LOCAL_WEB_PORT:-80}"
LOCAL_API_PORT="${LOCAL_API_PORT:-8080}"
REMOTE_WEB_PORT="${REMOTE_WEB_PORT:-3000}"
REMOTE_API_PORT="${REMOTE_API_PORT:-8080}"

# ---- 服务器配置（优先级：环境变量 > bin/server.env）----
ENV_FILE="$(cd "${FRP_DIR}/.." && pwd)/server.env"
if [ -f "${ENV_FILE}" ]; then
    _O_IP="${SERVER_IP:-}"; _O_USER="${SERVER_USER:-}"; _O_DOMAIN="${DOMAIN:-}"
    . "${ENV_FILE}"
    [ -n "$_O_IP" ]     && SERVER_IP="$_O_IP"
    [ -n "$_O_USER" ]   && SERVER_USER="$_O_USER"
    [ -n "$_O_DOMAIN" ] && DOMAIN="$_O_DOMAIN"
    unset _O_IP _O_USER _O_DOMAIN
fi
SSH_TARGET="${SERVER_IP:?请创建 bin/server.env（模板见 bin/server.env.example）}"
SSH_USER="${SERVER_USER:-ubuntu}"
# frpc 连接地址优先域名（换服务器 IP 不用重发配置），无域名回退 IP
FRPS_ADDR="${DOMAIN:-${SSH_TARGET}}"

# ---- 公共：从服务器读 frps token（提示走 stderr，输出走 stdout 供捕获）----
fetch_token() {
    echo "  从 frps 读取 auth.token（${SSH_USER}@${SSH_TARGET}）..." >&2
    local line
    line="$(ssh -o BatchMode=yes -o ConnectTimeout=10 \
        "${SSH_USER}@${SSH_TARGET}" \
        "sudo -n grep 'auth.token' /etc/frp/frps.toml" 2>/dev/null || true)"
    printf '%s\n' "${line}" | sed -n 's/^auth\.token *= *"\(.*\)".*/\1/p'
}

# ---- 公共：渲染 frpc 配置（$1=输出路径 $2=frp user 标识）----
# localIP 用 host.docker.internal：frpc 跑在容器里，127.0.0.1 指向容器自身；
# docker run 时配 --add-host=host.docker.internal:host-gateway 兼容 Linux，
# Mac/Windows Docker Desktop 原生支持该域名
# frps 上 proxy 全局唯一，靠 user 前缀区分成员（注册名为 user.name）；
# remotePort 先到先得，他人配置相同端口会注册失败并提示，天然防冲突
render_conf() {
    cat > "$1" <<EOF
# frpc 配置（由 bin/frp/install-frpc.sh 生成，含 token，勿外传/勿提交 Git）
serverAddr = "${FRPS_ADDR}"
serverPort = 7000
user = "$2"
auth.token = "${TOKEN}"

# 网络/服务器不稳时保持进程并自动重连
loginFailExit = false

# 关闭 mux（与 frps 一致）：每个代理独立 TCP 连接，规避单 mux 长连接劣化
transport.tcpMux = false

[[proxies]]
name = "panjia-web"
type = "tcp"
localIP = "host.docker.internal"
localPort = ${LOCAL_WEB_PORT}
remotePort = ${REMOTE_WEB_PORT}
transport.useEncryption = true

[[proxies]]
name = "panjia-api"
type = "tcp"
localIP = "host.docker.internal"
localPort = ${LOCAL_API_PORT}
remotePort = ${REMOTE_API_PORT}
transport.useEncryption = true
EOF
}

# ---- 公共：确保 panjia-frpc 镜像存在（本地构建：alpine + linux frpc 二进制）----
# 不用第三方 frp 镜像，避免国内 tag 不可达；镜像随本机架构构建（成员机器架构
# 与管理员相同时直接复用 tar，不同时成员侧重跑下载逻辑即可，见 gen 输出说明）
ensure_image() {
    if docker image inspect "${IMAGE_NAME}:${IMAGE_TAG}" >/dev/null 2>&1; then
        echo "[SKIP] 镜像已存在：${IMAGE_NAME}:${IMAGE_TAG}"
        return 0
    fi
    echo "构建镜像 ${IMAGE_NAME}:${IMAGE_TAG}..."
    case "$(uname -m)" in
        arm64) ARCH=arm64 ;;
        *)     ARCH=amd64 ;;
    esac
    TARBALL="frp_${FRP_VERSION}_linux_${ARCH}.tar.gz"
    INNER="frp_${FRP_VERSION}_linux_${ARCH}"
    URL_PRIMARY="https://github.com/fatedier/frp/releases/download/v${FRP_VERSION}/${TARBALL}"
    URL_MIRROR="https://mirror.ghproxy.com/${URL_PRIMARY}"
    TMP_DIR=$(mktemp -d)
    trap 'rm -rf "$TMP_DIR"' EXIT
    if ! curl -fsSL -m 300 -o "${TMP_DIR}/${TARBALL}" "${URL_PRIMARY}"; then
        echo "  GitHub 下载失败，尝试镜像..."
        curl -fsSL -m 300 -o "${TMP_DIR}/${TARBALL}" "${URL_MIRROR}" \
            || { echo "[ERROR] 两个下载源均失败：${URL_PRIMARY}"; exit 1; }
    fi
    tar xzf "${TMP_DIR}/${TARBALL}" -C "${TMP_DIR}" "${INNER}/frpc"
    mv "${TMP_DIR}/${INNER}/frpc" "${TMP_DIR}/frpc"
    chmod 755 "${TMP_DIR}/frpc"
    cat > "${TMP_DIR}/Dockerfile" <<DOCKER_EOF
FROM alpine:3.20
COPY frpc /usr/local/bin/frpc
CMD ["/usr/local/bin/frpc", "-c", "/etc/frp/frpc.toml"]
DOCKER_EOF
    docker build -t "${IMAGE_NAME}:${IMAGE_TAG}" "${TMP_DIR}" >/dev/null
    echo "  ✓ 镜像构建完成：${IMAGE_NAME}:${IMAGE_TAG}（frp $(docker run --rm --entrypoint frpc "${IMAGE_NAME}:${IMAGE_TAG}" -v 2>/dev/null || echo "${FRP_VERSION}")）"
}

# ---- 公共：导出成员镜像 tar（存在即跳过）----
export_image_tar() {
    mkdir -p "${DIST_DIR}"
    TAR_OUT="${DIST_DIR}/panjia-frpc-${FRP_VERSION}.tar"
    if [ -f "${TAR_OUT}" ]; then
        echo "[SKIP] 镜像 tar 已存在：${TAR_OUT}"
    else
        docker save -o "${TAR_OUT}" "${IMAGE_NAME}:${IMAGE_TAG}"
        echo "  ✓ 镜像已导出：${TAR_OUT}"
    fi
}

echo "=========================================="
if [ -n "${GEN_USER}" ]; then
    echo "  生成成员分发包：${GEN_USER}"
else
    echo "  frpc 安装（本机 Docker）"
fi
echo "  frps：${FRPS_ADDR}:7000"
echo "  映射：${REMOTE_WEB_PORT} → 本机:${LOCAL_WEB_PORT}（panjia-ui）"
echo "        ${REMOTE_API_PORT} → 本机:${LOCAL_API_PORT}（panjia-server）"
if [ "$RESET_CONFIG" = 1 ]; then
    echo "  模式：重置配置（重新拉取 token）"
fi
echo "=========================================="

# ==================== 成员分发包模式（--gen-user）====================
if [ -n "${GEN_USER}" ]; then
    if [ -z "${GEN_WEB_SPECIFIED}" ] || [ -z "${GEN_API_SPECIFIED}" ]; then
        echo "[ERROR] --gen-user 模式必须显式指定该成员的远端端口（每人不同）："
        echo "  REMOTE_WEB_PORT=3001 REMOTE_API_PORT=8081 sh bin/frp/install-frpc.sh --gen-user zhangsan"
        exit 1
    fi
    TOKEN="$(fetch_token)"
    if [ -z "${TOKEN}" ]; then
        echo "[ERROR] 无法读取 frps token（frps 可能尚未安装或 SSH/sudo 不可用）"
        echo "  先执行：sh bin/frp/install-frps.sh"
        exit 1
    fi
    ensure_image
    export_image_tar
    OUT="${DIST_DIR}/frpc-${GEN_USER}.toml"
    render_conf "${OUT}" "${GEN_USER}"
    chmod 600 "${OUT}"
    echo "  ✓ 成员配置已生成：${OUT}"
    echo ""
    echo "  分发给成员（toml 含 token，注意保密；两个文件放同一目录）："
    echo "    - ${OUT}"
    echo "    - panjia-frpc-${FRP_VERSION}.tar"
    echo ""
    echo "  成员侧接入（机器上只需 docker；PowerShell 同理）："
    echo "    docker load -i panjia-frpc-${FRP_VERSION}.tar"
    echo "    docker run -d --name ${CONTAINER_NAME} --restart unless-stopped \\"
    echo "      --add-host=host.docker.internal:host-gateway \\"
    echo "      -v ./frpc-${GEN_USER}.toml:/etc/frp/frpc.toml:ro \\"
    echo "      ${IMAGE_NAME}:${IMAGE_TAG}"
    echo ""
    echo "  成员系统部署好并启动后，访问："
    echo "    ui：  http://${FRPS_ADDR}:${REMOTE_WEB_PORT}"
    echo "    api： http://${FRPS_ADDR}:${REMOTE_API_PORT}"
    exit 0
fi

# ==================== 本机安装模式 ====================

# ---- 步骤 1：确保镜像 ----
echo ""
echo ">>> 步骤 1/4：准备镜像 ${IMAGE_NAME}:${IMAGE_TAG}"
ensure_image

# ---- 步骤 2：生成配置（从服务器读 token）----
if [ -f "${FRPC_CONF}" ] && [ "${RESET_CONFIG}" != "1" ]; then
    echo ""
    echo ">>> 步骤 2/4：[SKIP] ${FRPC_CONF} 已存在（token 不变，重置加 --reset-config）"
else
    echo ""
    echo ">>> 步骤 2/4：生成 ${FRPC_CONF}"
    if [ "${RESET_CONFIG}" = "1" ] && [ -f "${FRPC_CONF}" ]; then
        echo "  [FORCE] --reset-config 已指定，重新拉取 token 并重写配置"
    fi
    TOKEN="$(fetch_token)"
    if [ -z "${TOKEN}" ]; then
        echo "[ERROR] 无法读取 frps token（frps 可能尚未安装或 SSH/sudo 不可用）"
        echo "  先执行：sh bin/frp/install-frps.sh"
        exit 1
    fi
    render_conf "${FRPC_CONF}" "local"
    chmod 600 "${FRPC_CONF}"
    echo "  ✓ 配置已写入 ${FRPC_CONF}"
fi

# ---- 步骤 3：启动常驻容器 ----
echo ""
echo ">>> 步骤 3/4：启动容器（${CONTAINER_NAME}）"
# 先删旧容器（配置/镜像可能变了），与 bin/start.sh 前端容器同款幂等模式
docker rm -f "${CONTAINER_NAME}" >/dev/null 2>&1 || true
docker run -d \
    --name "${CONTAINER_NAME}" \
    --restart unless-stopped \
    --add-host=host.docker.internal:host-gateway \
    -v "${FRPC_CONF}:/etc/frp/frpc.toml:ro" \
    "${IMAGE_NAME}:${IMAGE_TAG}" >/dev/null
echo "  ✓ 已启动（docker 重启自动拉起，断线自动重连）"

# ---- 步骤 4：验证 ----
echo ""
echo ">>> 步骤 4/4：验证"
sleep 3
if docker ps --filter "name=${CONTAINER_NAME}" --filter "status=running" | grep -q "${CONTAINER_NAME}"; then
    echo "  ✓ 容器运行中"
else
    echo "  [ERROR] 容器未运行，日志尾部："
    docker logs --tail 20 "${CONTAINER_NAME}" 2>&1 | sed 's/^/    /' || true
    exit 1
fi

sleep 2
if docker logs "${CONTAINER_NAME}" 2>&1 | grep -q "login to server success"; then
    echo "  ✓ 已登录 frps（${FRPS_ADDR}:7000）"
else
    echo "  [WARN] 尚未看到登录成功日志（可能仍在重连），日志尾部："
    docker logs --tail 10 "${CONTAINER_NAME}" 2>&1 | sed 's/^/    /' || true
fi

if nc -z -w 3 "${SSH_TARGET}" 7000 >/dev/null 2>&1; then
    echo "  ✓ frps 端口 7000 连通"
else
    echo "  [WARN] 无法连通 ${SSH_TARGET}:7000（检查腾讯云安全组是否放行 7000/TCP）"
fi

for p in "${LOCAL_WEB_PORT}" "${LOCAL_API_PORT}"; do
    if nc -z -w 2 127.0.0.1 "${p}" >/dev/null 2>&1; then
        echo "  ✓ 本机端口 ${p} 在监听"
    else
        echo "  [WARN] 本机端口 ${p} 未监听（frp 只做转发，记得先在 IDE 启动 panjia-server / panjia-ui）"
    fi
done

echo ""
echo "=========================================="
echo "  ✓ frpc 安装完成"
echo "=========================================="
echo ""
echo "  外网访问："
echo "    ui 前端：  http://${FRPS_ADDR}:${REMOTE_WEB_PORT}   → 本机:${LOCAL_WEB_PORT}"
echo "    api 后端： http://${FRPS_ADDR}:${REMOTE_API_PORT}   → 本机:${LOCAL_API_PORT}"
echo "    dashboard：http://${FRPS_ADDR}:7500（admin，密码见 install-frps.sh 输出）"
echo ""
echo "  日志：docker logs -f ${CONTAINER_NAME}"
echo "  停止：docker rm -f ${CONTAINER_NAME}"
echo "  为成员生成分发包：REMOTE_WEB_PORT=3001 REMOTE_API_PORT=8081 sh bin/frp/install-frpc.sh --gen-user <名字>"
echo "=========================================="
