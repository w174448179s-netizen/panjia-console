#!/bin/bash
# ============================================================================
# 成员 FRP 分发包打包 —— 在本机 Mac 执行
#
# 作用：
#   为指定客户打出「一键接入包」，直接发给客户。客户解压后跑一条命令，
#   frpc 容器即接入 frps，外网通过 http://panjia.icu:3000+N 访问客户本机系统
#   （同源：页面 + API + 图片回显，全部走客户机自己的 nginx:80）。
#
# 用法：
#   sh bin/frp/pack-frpc.sh <客户名> <远端端口>
#   示例（1 号客户）：
#     sh bin/frp/pack-frpc.sh zhangsan 3001
#
# 端口约定（第 N 号客户，N 从 0 开始；0 号 = 管理员开发机，手工维护不入此流程）：
#   系统入口 = 3000 + N → 客户机 nginx:80（deploy/docker 整栈同源）
#   图片回显 domain_url = panjia.icu:3000+N/oss（客户机自己 nginx 的 /oss 反代）
#   不再需要 preview 站点 / api / minio 隧道——一个端口全包
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
GEN_USER="${1:?用法：sh bin/frp/pack-frpc.sh <客户名> <远端端口（第 N 号客户 = 3000+N）>}"
REMOTE_APP_PORT="${2:?缺少远端端口（第 N 号客户 = 3000+N）}"

case "${GEN_USER}" in
    *[!a-zA-Z0-9_-]*) echo "[ERROR] 客户名仅限字母/数字/下划线/中划线（收到：${GEN_USER}）"; exit 1 ;;
esac
case "${REMOTE_APP_PORT}" in
    30[0-9][0-9]) ;; *) echo "[ERROR] 远端端口须在 3000-3099（收到：${REMOTE_APP_PORT}）"; exit 1 ;;
esac
MEMBER_N=$((REMOTE_APP_PORT - 3000))

FRP_DIR="$(cd "$(dirname "$0")" && pwd)"
DIST_DIR="${FRP_DIR}/dist"

echo "=========================================="
echo "  客户分发包：${GEN_USER}（第 ${MEMBER_N} 号）"
echo "  隧道：${REMOTE_APP_PORT} → 客户机 nginx:80（整栈同源）"
echo "  外网入口：http://panjia.icu:${REMOTE_APP_PORT}"
echo "=========================================="

# ---- 1. 生成成员配置 + 确保镜像 tar（复用 install-frpc.sh --gen-user）----
echo ""
echo ">>> 1/3：生成成员配置与镜像..."
REMOTE_APP_PORT="${REMOTE_APP_PORT}" \
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
echo "访问（同源入口，页面/API/图片）：http://panjia.icu:${REMOTE_APP_PORT}"
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
  http://panjia.icu:${REMOTE_APP_PORT}

接入步骤：
  1. 机器上安装 docker（Mac/Windows 均为 Docker Desktop）
  2. 用标准 deploy/docker 整栈部署盘家系统，确保 WEB_PORT 对外发布
     （默认 "80:80"；nginx 同源服务：前端 + /prod-api + /oss）
  3. 系统里配置文件访问域名（「文件配置管理」minio 行，或跑 deploy/docker/fix-oss.sh）：
       domain_url = panjia.icu:${REMOTE_APP_PORT}/oss
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
echo "  发给客户后，客户解压进入目录："
echo "    Mac/Linux：sh install-frpc.sh"
echo "    Windows：  双击 install-frpc.bat"
echo "  访问：http://panjia.icu:${REMOTE_APP_PORT}（同源入口）"
echo ""
echo "  ★ 端口分配台账：${GEN_USER} → 第${MEMBER_N}号：入口 ${REMOTE_APP_PORT}（domain_url = panjia.icu:${REMOTE_APP_PORT}/oss）"
echo "  ★ 提醒：腾讯云安全组放行 ${REMOTE_APP_PORT}/TCP（若未放行整段 3000-3099）"
echo "  ★ 客户机前置：deploy/docker 整栈已跑、WEB_PORT 对外发布（如 \"80:80\"）"
echo "=========================================="
