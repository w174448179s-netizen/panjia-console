#!/bin/bash
# ============================================================================
# 只推送前端（不动后端、不动数据库）
#
# 适用场景：改完 Vue 代码后,快速把 ui/dist/ 同步到服务器
#
# 用法：sh bin/push-frontend.sh <服务器IP> <SSH用户>
#
# 流程：本地构建(若需要)→ rsync 增量同步 → nginx reload
# ============================================================================

set -e

SERVER_ADDR="${1:?用法：sh bin/push-frontend.sh <服务器IP> <SSH用户> [镜像标签]}"
SSH_USER="${2:?请提供 SSH 用户名（如 ubuntu）}"
IMAGE_TAG="${3:-v1}"

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
INSTALL_DIR="/opt/panjia-console"

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
