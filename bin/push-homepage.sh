#!/bin/bash
# ============================================================================
# 推送官网首页（ui/homepage/ → 服务器 web/home，公开访问）
#
# 官网是纯静态单页（index.html 内联样式），无需构建，改完直接推。
# 管理后台在 /console/（ui/dist/，用 push-frontend.sh 推送）。
#
# 用法：
#   sh bin/push-homepage.sh                       # 服务器信息读 bin/server.env（推荐）
#   sh bin/push-homepage.sh <服务器IP> <SSH用户>   # 旧写法，兼容
#
# 流程：rsync 同步 → nginx reload
# ============================================================================

set -e

# ---- 参数：地址/用户可省略，省略时读 bin/server.env ----
. "$(dirname "$0")/_server-env.sh"
SERVER_ADDR=""; SSH_USER=""
case "${1:-}" in
    [0-9]*.[0-9]*.[0-9]*.[0-9]*)
        SERVER_ADDR="$1"; shift
        case "${1:-}" in
            ""|-*|*.*) ;;   # 空/flag/含点(域名)→不当作用户名
            *) SSH_USER="$1"; shift ;;
        esac ;;
esac
SERVER_ADDR="${SERVER_ADDR:-${SERVER_IP:?请创建 bin/server.env（模板见 bin/server.env.example），或传入参数 <服务器IP>}}"
SSH_USER="${SSH_USER:-${SERVER_USER:-ubuntu}}"

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
INSTALL_DIR="${INSTALL_DIR:-/opt/panjia-console}"   # 可在 bin/server.env 配置

# 1. 源文件检查
if [ ! -f "$PROJECT_DIR/ui/homepage/index.html" ]; then
    echo "[ERROR] ui/homepage/index.html 不存在" >&2
    exit 1
fi

# 2. rsync 同步（--delete 清理远端多余文件；官网目录由 docker-compose 挂载为 /var/www/home）
rsync -az --delete --rsync-path="sudo rsync" \
    "$PROJECT_DIR/ui/homepage/" \
    "${SSH_USER}@${SERVER_ADDR}:${INSTALL_DIR}/web/home/"

# 3. nginx reload
ssh -o ConnectTimeout=10 "${SSH_USER}@${SERVER_ADDR}" \
    "sudo docker exec panjia-console-web nginx -s reload" \
    2>/dev/null && echo "  ✓ nginx reloaded" || echo "  [WARN] nginx reload 失败(可能容器未运行)"

echo ""
echo "=========================================="
echo "  ✓ 官网首页推送完成"
echo "  访问：https://www.panjia.icu/  （或 https://panjia.icu/）"
echo "=========================================="
