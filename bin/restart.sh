#!/bin/bash
#
# 重启 panjia-console（前端 + 后端）
# 用法：sh bin/restart.sh [镜像标签]
# 默认标签：latest
#

set -e

IMAGE_TAG="${1:-latest}"
PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"

echo "=========================================="
echo "  重启 panjia-console"
echo "  镜像标签：$IMAGE_TAG"
echo "=========================================="

sh "$PROJECT_DIR/bin/stop.sh"
sh "$PROJECT_DIR/bin/start.sh" "$IMAGE_TAG"
