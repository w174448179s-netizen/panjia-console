#!/bin/bash
# ============================================================================
# FRP 服务端（frps）安装 —— Docker 容器方式，从本机 Mac 执行
# SSH 到 bin/server.env 指定的服务器安装
#
# 作用：
#   在云服务器上以 Docker 容器常驻运行 frps，供各成员本机的 frpc（容器）
#   反向连接，外网通过「panjia.icu:端口」访问成员各自的本机系统。
#
# 用法：
#   sh bin/frp/install-frps.sh [--reset-config]
#
#     --reset-config   重新生成 /etc/frp/frps.toml（token/dashboard 密码会变更，
#                      重置后需重新给成员打包 / 成员重连）
#
# 幂等性：可重复执行
#   - 镜像已存在 → [SKIP] 构建；版本变更则构建新 tag 并重建容器
#   - /etc/frp/frps.toml 已存在 → 保留（token 不变），--reset-config 才重建
#   - docker-compose.yml 无变化且无配置/镜像变更时不重建容器，不踢断现有隧道
#   - 旧版 systemd 裸机 frps 自动清理迁移（配置文件保留复用）
#
# 服务器落点：
#   /etc/frp/frps.toml                配置（随机 token + dashboard 随机密码，
#                                     挂载进容器，路径与 frpc 脚本读取保持一致）
#   /opt/panjia-frp/docker-compose.yml   frps 容器编排
#   镜像/容器：panjia-frps:<版本> / panjia-frps
#
# 端口规划（需在腾讯云安全组放行 TCP）：
#   7000        所有 frpc 的连接端口（token 共享，天然支持多客户端同时接入）
#   7500        dashboard（admin / 随机密码，脚本结束时输出）
#   3000-3099   ui 映射端口池：第 N 号成员 → 3000+N（0 号 = 3000）
#   8080-8099   api 映射端口池：第 N 号成员 → 8080+N（0 号 = 8080）
#
# 前置条件：
#   1. 服务器已装 docker（bin/setup-server.sh 已装；未装会报错提示）
#   2. SSH 免密 + sudo NOPASSWD（同其他 bin/*.sh）
# ============================================================================

set -e

FRP_VERSION="0.71.0"   # frp 版本（升级改这里，与 install-frpc.sh / pack-frpc.sh 保持一致）

RESET_CONFIG=0
for arg in "$@"; do
    case "$arg" in
        --reset-config) RESET_CONFIG=1 ;;
        --help|-h)
            awk 'NR==1{next} /^#/{sub(/^# ?/,""); print; next} {exit}' "$0"
            exit 0 ;;
        *) echo "[ERROR] 未知参数：$arg"; exit 1 ;;
    esac
done

# ---- 服务器配置（优先级：环境变量 > bin/server.env，与 bin/*.sh 一致）----
ENV_FILE="$(cd "$(dirname "$0")/.." && pwd)/server.env"
if [ -f "$ENV_FILE" ]; then
    _O_IP="${SERVER_IP:-}"; _O_USER="${SERVER_USER:-}"
    . "$ENV_FILE"
    [ -n "$_O_IP" ]   && SERVER_IP="$_O_IP"
    [ -n "$_O_USER" ] && SERVER_USER="$_O_USER"
    unset _O_IP _O_USER
fi
SERVER_ADDR="${SERVER_IP:?请创建 bin/server.env（模板见 bin/server.env.example）}"
SSH_USER="${SERVER_USER:-ubuntu}"

echo "=========================================="
echo "  frps 安装（Docker）→ $SSH_USER@$SERVER_ADDR"
echo "  frp 版本：$FRP_VERSION"
if [ "$RESET_CONFIG" = 1 ]; then
    echo "  模式：重置配置（token / dashboard 密码将变更）"
fi
echo "=========================================="

# ---- SSH / sudo 预检（同 setup-server.sh）----
if ! ssh -o BatchMode=yes -o ConnectTimeout=10 "${SSH_USER}@${SERVER_ADDR}" "echo ok" >/dev/null 2>&1; then
    echo "[ERROR] 无法 SSH 连接 ${SSH_USER}@${SERVER_ADDR}（先执行：ssh-copy-id ${SSH_USER}@${SERVER_ADDR}）"
    exit 1
fi
if [ "$SSH_USER" = "root" ]; then
    SUDO_PREFIX=""
else
    if ! ssh -o BatchMode=yes -o ConnectTimeout=10 "${SSH_USER}@${SERVER_ADDR}" "sudo -n true" 2>/dev/null; then
        echo "[ERROR] 用户 ${SSH_USER} 无免密 sudo"
        echo "  配置方法：ssh ${SSH_USER}@${SERVER_ADDR} 'echo \"${SSH_USER} ALL=(ALL) NOPASSWD:ALL\" | sudo tee /etc/sudoers.d/${SSH_USER}'"
        exit 1
    fi
    SUDO_PREFIX="sudo -n"
fi

# ---- 远程安装（变量经 sudo 前缀注入远程环境；heredoc 加引号避免本地展开）----
echo ""
echo ">>> 远程安装 frps..."
ssh -o BatchMode=yes -o ConnectTimeout=10 -o ServerAliveInterval=30 -o ServerAliveCountMax=3 \
    "${SSH_USER}@${SERVER_ADDR}" \
    "${SUDO_PREFIX} FRP_VERSION='${FRP_VERSION}' RESET_CONFIG='${RESET_CONFIG}' bash -s" <<'REMOTE'
set -e

echo "[服务器] 系统：$(. /etc/os-release 2>/dev/null && echo "$ID $VERSION_ID" || uname -sr)"

# ---------- docker 预检（console 服务器已装；独立新机器需先跑 setup-server.sh）----------
if ! command -v docker >/dev/null 2>&1; then
    echo "[ERROR] 服务器未安装 docker，请先执行：sh bin/setup-server.sh"
    exit 1
fi
systemctl is-active --quiet docker || systemctl start docker
for i in 1 2 3 4 5; do
    docker info >/dev/null 2>&1 && break
    sleep 1
done
docker info >/dev/null 2>&1 || { echo "[ERROR] docker daemon 未就绪"; exit 1; }
echo "[服务器] ✓ docker 就绪"

# ---------- 旧版 systemd 裸机 frps 迁移清理（首次容器化时触发一次）----------
if [ -f /etc/systemd/system/frps.service ] || [ -x /usr/local/bin/frps ]; then
    systemctl stop frps 2>/dev/null || true
    systemctl disable frps 2>/dev/null || true
    rm -f /etc/systemd/system/frps.service /usr/local/bin/frps
    systemctl daemon-reload 2>/dev/null || true
    echo "[服务器] ✓ 旧版 systemd frps 已清理（/etc/frp/frps.toml 保留复用）"
fi

ARCH=amd64
case "$(uname -m)" in
    aarch64|arm64) ARCH=arm64 ;;
esac
TARBALL="frp_${FRP_VERSION}_linux_${ARCH}.tar.gz"
INNER="frp_${FRP_VERSION}_linux_${ARCH}"
URL_PRIMARY="https://github.com/fatedier/frp/releases/download/v${FRP_VERSION}/${TARBALL}"
URL_MIRROR="https://mirror.ghproxy.com/${URL_PRIMARY}"

# ---------- 构建 frps 镜像（alpine + 二进制，不依赖第三方 frp 镜像）----------
IMAGE_NEW=0
if docker image inspect "panjia-frps:${FRP_VERSION}" >/dev/null 2>&1; then
    echo "[SKIP] 镜像已存在：panjia-frps:${FRP_VERSION}"
else
    echo "[服务器] 下载并构建 panjia-frps:${FRP_VERSION} (linux/${ARCH})..."
    TMP_DIR=$(mktemp -d)
    trap 'rm -rf "$TMP_DIR"' EXIT
    if ! curl -fsSL -m 300 -o "${TMP_DIR}/${TARBALL}" "${URL_PRIMARY}"; then
        echo "[服务器] GitHub 下载失败，尝试镜像..."
        curl -fsSL -m 300 -o "${TMP_DIR}/${TARBALL}" "${URL_MIRROR}" \
            || { echo "[ERROR] 两个下载源均失败，可手工下载解压 frps 放到 ${TMP_DIR} 后 docker build：${URL_PRIMARY}"; exit 1; }
    fi
    tar xzf "${TMP_DIR}/${TARBALL}" -C "${TMP_DIR}" "${INNER}/frps"
    mv "${TMP_DIR}/${INNER}/frps" "${TMP_DIR}/frps"
    chmod 755 "${TMP_DIR}/frps"
    cat > "${TMP_DIR}/Dockerfile" <<DOCKER_EOF
FROM alpine:3.20
COPY frps /usr/local/bin/frps
CMD ["/usr/local/bin/frps", "-c", "/etc/frp/frps.toml"]
DOCKER_EOF
    docker build -t "panjia-frps:${FRP_VERSION}" "${TMP_DIR}" >/dev/null
    IMAGE_NEW=1
    echo "[服务器] ✓ 镜像构建完成：panjia-frps:${FRP_VERSION}"
fi

# ---------- 配置（关键幂等点：默认保留现有 toml，token 不变）----------
mkdir -p /etc/frp
chmod 700 /etc/frp
CONFIG_CHANGED=0
TOKEN=""
DASH_PWD=""
if [ -f /etc/frp/frps.toml ] && [ "${RESET_CONFIG}" != "1" ]; then
    echo "[SKIP] /etc/frp/frps.toml 已存在，保留现有配置（token 不变）"
    TOKEN=$(sed -n 's/^auth\.token *= *"\(.*\)".*/\1/p' /etc/frp/frps.toml)
    DASH_PWD=$(sed -n 's/^webServer\.password *= *"\(.*\)".*/\1/p' /etc/frp/frps.toml)
    if [ -z "${TOKEN}" ]; then
        echo "[WARN] 未能从现有配置解析出 token（如需重建：加 --reset-config 重跑）"
    fi
else
    if [ "${RESET_CONFIG}" = "1" ] && [ -f /etc/frp/frps.toml ]; then
        echo "[FORCE] --reset-config 已指定，重建配置（token / dashboard 密码已变更！）"
    fi
    TOKEN=$(openssl rand -hex 16)
    DASH_PWD=$(openssl rand -hex 8)
    cat > /etc/frp/frps.toml <<TOML_EOF
# frps 配置（由 bin/frp/install-frps.sh 生成）
bindAddr = "0.0.0.0"
bindPort = 7000

# 关闭 mux（须与 frpc 一致）：每个代理独立 TCP 连接，规避单 mux 长连接劣化
# 注意：改此文件后须重建容器才生效（单文件挂载指向旧 inode，restart 无效）
transport.tcpMux = false

auth.token = "${TOKEN}"

# dashboard：http://<服务器IP>:7500
webServer.addr = "0.0.0.0"
webServer.port = 7500
webServer.user = "admin"
webServer.password = "${DASH_PWD}"

# 允许 frpc 映射的远端端口池（每人分配不同端口；改了要同步腾讯云安全组放行）
allowPorts = [
  { start = 3000, end = 3099 },
  { start = 8080, end = 8099 }
]
TOML_EOF
    chmod 600 /etc/frp/frps.toml
    CONFIG_CHANGED=1
    echo "[服务器] ✓ 配置已写入 /etc/frp/frps.toml"
fi

# ---------- docker-compose 编排 ----------
mkdir -p /opt/panjia-frp
COMPOSE_TMP=$(mktemp)
cat > "${COMPOSE_TMP}" <<COMPOSE_EOF
services:
  frps:
    image: panjia-frps:${FRP_VERSION}
    container_name: panjia-frps
    restart: unless-stopped
    ports:
      - "7000:7000"
      - "7500:7500"
      - "3000-3099:3000-3099"
      - "8080-8099:8080-8099"
    volumes:
      - /etc/frp/frps.toml:/etc/frp/frps.toml:ro
COMPOSE_EOF
COMPOSE_CHANGED=0
if [ -f /opt/panjia-frp/docker-compose.yml ] && cmp -s "${COMPOSE_TMP}" /opt/panjia-frp/docker-compose.yml; then
    echo "[SKIP] docker-compose.yml 无变化"
    rm -f "${COMPOSE_TMP}"
else
    mv "${COMPOSE_TMP}" /opt/panjia-frp/docker-compose.yml
    COMPOSE_CHANGED=1
    echo "[服务器] ✓ docker-compose.yml 已写入 /opt/panjia-frp/"
fi

# ---------- 启动（仅必要时 force-recreate，避免踢断现有隧道）----------
cd /opt/panjia-frp
if [ "${CONFIG_CHANGED}" = "1" ] || [ "${IMAGE_NEW}" = "1" ] || [ "${COMPOSE_CHANGED}" = "1" ]; then
    docker compose up -d --force-recreate
    echo "[服务器] ✓ frps 容器已重建"
else
    docker compose up -d
    echo "[SKIP] 无任何变更，容器保持运行"
fi

sleep 2
if docker ps --filter "name=panjia-frps" --filter "status=running" | grep -q panjia-frps; then
    echo "[服务器] ✓ frps 容器运行中（日志：docker logs -f panjia-frps）"
else
    echo "[ERROR] frps 启动失败，最近日志："
    docker logs --tail 20 panjia-frps 2>&1 || true
    echo "  常见原因：宿主机端口被占用（ss -tlnp | grep -E ':7000|:7500'）"
    exit 1
fi

# ---------- 防火墙 ----------
if command -v ufw >/dev/null 2>&1 && ufw status 2>/dev/null | grep -q active; then
    ufw allow 7000/tcp      comment "frps"           >/dev/null 2>&1 || true
    ufw allow 7500/tcp      comment "frps-dashboard" >/dev/null 2>&1 || true
    ufw allow 3000:3099/tcp comment "frp-web"        >/dev/null 2>&1 || true
    ufw allow 8080:8099/tcp comment "frp-api"        >/dev/null 2>&1 || true
    echo "[服务器] ✓ UFW 已放行 7000 / 7500 / 3000-3099 / 8080-8099"
else
    echo "[服务器] UFW 未启用，跳过（端口放行需在腾讯云安全组配置）"
fi

echo ""
echo "[服务器] auth.token = ${TOKEN}"
echo "[服务器] dashboard 密码 = ${DASH_PWD}"
REMOTE

echo ""
echo "=========================================="
echo "  ✓ frps 安装完成"
echo "=========================================="
echo ""
echo "  frpc 连接地址：${SERVER_ADDR}:7000"
echo "  dashboard：    http://${SERVER_ADDR}:7500（admin，密码见上方输出）"
echo ""
echo "  运维：ssh ${SSH_USER}@${SERVER_ADDR}"
echo "    日志：docker logs -f panjia-frps"
echo "    重启：docker restart panjia-frps"
echo "    编排：/opt/panjia-frp/docker-compose.yml"
echo ""
echo "  ★ 腾讯云安全组需放行（TCP）：7000、7500、3000-3099、8080-8099"
if [ "$RESET_CONFIG" = 1 ]; then
    echo "  ★ 本次重置了配置：成员 toml 需重新打包分发（pack-frpc.sh），frpc 重连后生效"
else
    echo "  ★ 下一步：sh bin/frp/install-frpc.sh   # 本机安装 frpc 并挂载 ui/api"
fi
echo "=========================================="
