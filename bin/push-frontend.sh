#!/bin/bash
# ============================================================================
# 只推送前端（不动后端、不动数据库）
#
# 适用场景：改完 Vue 代码后,快速把 ui/dist/ 同步到服务器
#
# 用法：
#   sh bin/push-frontend.sh                  # 服务器信息读 bin/server.env（推荐）
#   sh bin/push-frontend.sh <服务器IP> <SSH用户>   # 旧写法，兼容
#
# 流程：本地构建(若需要)→ rsync 增量同步 → nginx reload
# ============================================================================

set -e

# ---- 参数：地址/用户可省略，省略时读 bin/server.env ----
. "$(dirname "$0")/_server-env.sh"
SERVER_ADDR=""; SSH_USER=""
case "${1:-}" in
    [0-9]*.[0-9]*.[0-9]*.[0-9]*)
        SERVER_ADDR="$1"; shift
        case "${1:-}" in
            ""|-*|*.*) ;;   # 空/flag/含点(域名或tag)→不当作用户名
            *) SSH_USER="$1"; shift ;;
        esac ;;
esac
SERVER_ADDR="${SERVER_ADDR:-${SERVER_IP:?请创建 bin/server.env（模板见 bin/server.env.example），或传入参数 <服务器IP>}}"
SSH_USER="${SSH_USER:-${SERVER_USER:-ubuntu}}"
IMAGE_TAG="${1:-${IMAGE_TAG:-v1}}"   # 优先级：参数 > 环境变量 > server.env > v1

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
INSTALL_DIR="${INSTALL_DIR:-/opt/panjia-console}"   # 可在 bin/server.env 配置

# 1. 本地构建产物检查: dist 缺失 或 源码比 dist 新 → 重新构建
#    (只看"dist 存不存在"会推旧构建,漏掉刚改的代码)
if [ ! -f "$PROJECT_DIR/ui/dist/index.html" ] || \
   [ -n "$(find "$PROJECT_DIR/ui/src" -newer "$PROJECT_DIR/ui/dist/index.html" -print -quit 2>/dev/null)" ]; then
    echo "ui/dist/ 缺失或源码有更新,触发前端构建..."
    sh "$PROJECT_DIR/bin/build.sh" "$IMAGE_TAG" --frontend
else
    echo "  ✓ ui/dist/ 已是最新"
fi

# 2. rsync 增量同步 + 自动清理远端旧文件
#    --delete: 旧版本独有文件会被删(避免僵尸路由/缓存)
#    --rsync-path="sudo rsync": 写到 /opt/panjia-console/web/dist/ 需要 sudo
#    注: rsync 不会 rm 目录,只删文件 → web/dist inode 保留 → nginx mount 不会失效
rsync -az --delete --rsync-path="sudo rsync" \
    "$PROJECT_DIR/ui/dist/" \
    "${SSH_USER}@${SERVER_ADDR}:${INSTALL_DIR}/web/dist/"

# 3. nginx reload 让 worker 进程重新打开被替换的文件
ssh -o ConnectTimeout=10 "${SSH_USER}@${SERVER_ADDR}" \
    "sudo docker exec panjia-console-web nginx -s reload" \
    2>/dev/null && echo "  ✓ nginx reloaded" || echo "  [WARN] nginx reload 失败(可能容器未运行)"
