#!/bin/bash
#
# 停止 panjia-console（前端 + 后端）
# 用法：sh bin/stop.sh
#

set -e

BACKEND_CONTAINER="panjia-console"
FRONTEND_CONTAINER="panjia-console-web"

echo "=========================================="
echo "  停止 panjia-console"
echo "=========================================="

stopped=false

if docker ps -q --filter "name=$FRONTEND_CONTAINER" | grep -q .; then
    echo ""
    echo "停止前端容器..."
    docker stop "$FRONTEND_CONTAINER"
    stopped=true
fi

if docker ps -q --filter "name=$BACKEND_CONTAINER" | grep -q .; then
    echo ""
    echo "停止后端容器..."
    docker stop "$BACKEND_CONTAINER"
    stopped=true
fi

if [ "$stopped" = true ]; then
    echo ""
    echo "=========================================="
    echo "  ✓ 已全部停止"
    echo "=========================================="
else
    echo ""
    echo "[INFO] 没有运行中的容器"
fi
