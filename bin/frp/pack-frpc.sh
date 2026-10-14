#!/bin/bash
# ============================================================================
# 成员 FRP 分发包打包 —— 在本机 Mac 执行
#
# 作用：
#   为指定成员打出「一键接入包」，直接发给成员。成员解压后跑一条命令，
#   frpc 容器即接入 frps，外网通过 http://panjia.icu:3100+N 访问成员本机系统
#   （同源：页面 + API + 图片回显）。
#
# 用法：
#   sh bin/frp/pack-frpc.sh <成员名> <api远端端口> <minio远端端口>
#   示例（1 号成员）：
#     sh bin/frp/pack-frpc.sh zhangsan 8081 3011
#
# 端口约定（第 N 号成员，N 从 0 开始；0 号 = 管理员自己）：
#   系统入口 = 3100 + N（preview nginx，前端全员共享，无需隧道）
#   api   = 8080 + N → 成员本机后端 8080（preview /prod-api 反代用）
#   minio = 3010 + N → 成员本机 MinIO 9000（preview /minio 反代用，图片回显）
#
# 包内容（dist/panjia-frpc-<成员名>-<版本>.zip）：
#   frpc-<成员名>.toml            成员专属配置（含 token，注意保密）
#   panjia-frpc-<版本>.tar        frpc docker 镜像
#   install-frpc.sh               Mac/Linux 成员一键启动
#   install-frpc.bat              Windows 成员一键启动（CRLF）
#   README.txt                    接入说明
#
# 幂等性：可重复执行，重复打包覆盖旧包；镜像只在缺失时构建/导出一次
# 前置条件：服务器已安装 frps（install-frps.sh）；本机 docker 可用
# ============================================================================

set -e

FRP_VERSION="0.71.0"   # 与 install-frpc.sh 保持一致
IMAGE_NAME="panjia-frpc"
CONTAINER_NAME="panjia-frpc"

for arg in "$@"; do
    case "$arg" in
        --help|-h)
            awk 'NR==1{next} /^#/{sub(/^# ?/,""); print; next} {exit}' "$0"
            exit 0 ;;
    esac
done

# ---- 参数 ----
GEN_USER="${1:?用法：sh bin/frp/pack-frpc.sh <成员名> <api远端端口> <minio远端端口>}"
REMOTE_API_PORT="${2:?缺少 api 远端端口（第 N 号成员 = 8080+N）}"
REMOTE_MINIO_PORT="${3:?缺少 minio 远端端口（第 N 号成员 = 3010+N）}"

case "${GEN_USER}" in
    *[!a-zA-Z0-9_-]*) echo "[ERROR] 成员名仅限字母/数字/下划线/中划线（收到：${GEN_USER}）"; exit 1 ;;
esac
case "${REMOTE_API_PORT}" in
    80[89][0-9]) ;; *) echo "[ERROR] api 远端端口须在 8080-8099（收到：${REMOTE_API_PORT}）"; exit 1 ;;
esac
case "${REMOTE_MINIO_PORT}" in
    30[0-9][0-9]) ;; *) echo "[ERROR] minio 远端端口须在 3000-3099（收到：${REMOTE_MINIO_PORT}）"; exit 1 ;;
esac
MEMBER_N=$((REMOTE_API_PORT - 8080))
if [ "$((REMOTE_MINIO_PORT - 3010))" != "${MEMBER_N}" ]; then
    echo "[ERROR] 成员号不一致：api 端口对应第 ${MEMBER_N} 号，minio 端口应对应 3010+${MEMBER_N}=$((3010 + MEMBER_N))"; exit 1
fi

FRP_DIR="$(cd "$(dirname "$0")" && pwd)"
DIST_DIR="${FRP_DIR}/dist"

echo "=========================================="
echo "  成员分发包：${GEN_USER}（第 ${MEMBER_N} 号）"
echo "  隧道：${REMOTE_API_PORT} → 本机后端 8080，${REMOTE_MINIO_PORT} → 本机 MinIO 9000"
echo "  外网入口：http://panjia.icu:$((3100 + MEMBER_N))"
echo "=========================================="

# ---- 1. 生成成员配置 + 确保镜像 tar（复用 install-frpc.sh --gen-user）----
echo ""
echo ">>> 1/3：生成成员配置与镜像..."
REMOTE_API_PORT="${REMOTE_API_PORT}" REMOTE_MINIO_PORT="${REMOTE_MINIO_PORT}" \
    sh "${FRP_DIR}/install-frpc.sh" --gen-user "${GEN_USER}" >/dev/null
[ -f "${DIST_DIR}/frpc-${GEN_USER}.toml" ]            || { echo "[ERROR] 成员配置生成失败"; exit 1; }
[ -f "${DIST_DIR}/panjia-frpc-${FRP_VERSION}.tar" ]   || { echo "[ERROR] 镜像 tar 导出失败"; exit 1; }

# ---- 2. 组装包内容 ----
echo ""
echo ">>> 2/3：组装包内容..."
STAGE=$(mktemp -d)
trap 'rm -rf "$STAGE"' EXIT
PKG_DIR="${STAGE}/panjia-frpc-${GEN_USER}"
mkdir -p "${PKG_DIR}"
cp "${DIST_DIR}/frpc-${GEN_USER}.toml" "${PKG_DIR}/"
cp "${DIST_DIR}/panjia-frpc-${FRP_VERSION}.tar" "${PKG_DIR}/"

# Mac/Linux 成员一键启动（外层 heredoc 展开版本/成员名，内层 $() 转义留给成员机器执行）
cat > "${PKG_DIR}/install-frpc.sh" <<EOF
#!/bin/bash
# 盘家 FRP 接入脚本（${GEN_USER}）—— 机器上需已安装 docker
set -e
cd "\$(dirname "\$0")"

echo "[1/2] 导入 frpc 镜像..."
docker load -i panjia-frpc-${FRP_VERSION}.tar

echo "[2/2] 启动 frpc 容器..."
docker rm -f ${CONTAINER_NAME} >/dev/null 2>&1 || true
docker run -d --name ${CONTAINER_NAME} \\
    --restart unless-stopped \\
    --add-host=host.docker.internal:host-gateway \\
    -v "\$(pwd)/frpc-${GEN_USER}.toml:/etc/frp/frpc.toml:ro" \\
    ${IMAGE_NAME}:${FRP_VERSION}

sleep 3
echo ""
echo "容器状态："
docker ps --filter name=${CONTAINER_NAME}
echo ""
echo "最近日志（出现 login to server success 即接入成功）："
docker logs --tail 5 ${CONTAINER_NAME} 2>&1
echo ""
echo "访问（同源入口，页面/API/图片）：http://panjia.icu:$((3100 + MEMBER_N))"
EOF
chmod 755 "${PKG_DIR}/install-frpc.sh"

# Windows 成员一键启动
cat > "${PKG_DIR}/install-frpc.bat" <<EOF
@echo off
rem 盘家 FRP 接入脚本（${GEN_USER}）—— 机器上需已安装 Docker Desktop
cd /d %~dp0

echo [1/2] 导入 frpc 镜像...
docker load -i panjia-frpc-${FRP_VERSION}.tar

echo [2/2] 启动 frpc 容器...
docker rm -f ${CONTAINER_NAME} 2>nul
docker run -d --name ${CONTAINER_NAME} --restart unless-stopped --add-host=host.docker.internal:host-gateway -v "%cd%\frpc-${GEN_USER}.toml:/etc/frp/frpc.toml:ro" ${IMAGE_NAME}:${FRP_VERSION}

timeout /t 3 /nobreak >nul
docker ps --filter name=${CONTAINER_NAME}
docker logs --tail 5 ${CONTAINER_NAME}
echo.
echo 日志出现 login to server success 即接入成功。
pause
EOF
# bat 转 CRLF（Windows cmd 兼容）
sed 's/$/\r/' "${PKG_DIR}/install-frpc.bat" > "${PKG_DIR}/.bat.tmp" \
    && mv "${PKG_DIR}/.bat.tmp" "${PKG_DIR}/install-frpc.bat"

# 接入说明
cat > "${PKG_DIR}/README.txt" <<EOF
盘家系统 FRP 接入包（${GEN_USER}）
====================================

访问地址（接入完成后，同源入口：页面/API/图片）：
  http://panjia.icu:$((3100 + MEMBER_N))

接入步骤：
  1. 机器上安装 docker（Mac/Windows 均为 Docker Desktop）
  2. 把盘家系统在本机跑起来，并确保宿主机以下端口在监听：
       后端 8080、MinIO 9000
     （docker compose 部署的，给 server 服务加 ports: "8080:8080"）
  3. 系统里配置图片回显域名（文件配置管理 minio 行）：
       domain_url = panjia.icu:$((3100 + MEMBER_N))/minio
  4. 运行接入脚本：
       Mac / Linux： sh install-frpc.sh
       Windows：     双击 install-frpc.bat
  5. 日志出现 login to server success 即接入成功

日常：
  查看日志：docker logs -f ${CONTAINER_NAME}
  停止：    docker rm -f ${CONTAINER_NAME}
  重新接入：再次运行接入脚本即可

注意：frpc-*.toml 含接入凭证，请勿外传。
EOF

# ---- 3. 压缩（zip 最通用，缺失时兜底 tar.gz）----
echo ""
echo ">>> 3/3：压缩..."
ZIP_OUT="${DIST_DIR}/panjia-frpc-${GEN_USER}-${FRP_VERSION}.zip"
rm -f "${ZIP_OUT}"
if command -v zip >/dev/null 2>&1; then
    (cd "${STAGE}" && zip -qr "${ZIP_OUT}" "$(basename "${PKG_DIR}")")
else
    ZIP_OUT="${DIST_DIR}/panjia-frpc-${GEN_USER}-${FRP_VERSION}.tar.gz"
    rm -f "${ZIP_OUT}"
    tar czf "${ZIP_OUT}" -C "${STAGE}" "$(basename "${PKG_DIR}")"
fi

echo ""
echo "=========================================="
echo "  ✓ 分发包已生成：${ZIP_OUT}（$(du -h "${ZIP_OUT}" | cut -f1)）"
echo "=========================================="
echo ""
echo "  发给成员后，成员解压进入目录："
echo "    Mac/Linux：sh install-frpc.sh"
echo "    Windows：  双击 install-frpc.bat"
echo "  访问：http://panjia.icu:$((3100 + MEMBER_N))（同源入口）"
echo ""
echo "  ★ 端口分配台账：${GEN_USER} → 第${MEMBER_N}号：入口 $((3100 + MEMBER_N)) / api ${REMOTE_API_PORT} / minio ${REMOTE_MINIO_PORT}"
echo "  ★ 提醒：部署后执行 sh bin/frp/deploy-preview.sh ${MEMBER_N} 生成该成员的 preview 站点"
echo "  ★ 提醒：腾讯云安全组放行 $((3100 + MEMBER_N))/TCP（若未放行整段 3100-3199）"
echo "=========================================="
