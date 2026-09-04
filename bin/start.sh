#!/bin/bash
#
# 本地启动 panjia-console（前端 nginx + 后端）
# 用法：sh bin/start.sh [镜像标签]
# 默认标签：latest
#
# 说明：
#   - 后端容器：panjia-console（端口 8081 映射到 8080）
#   - 前端容器：nginx:stable-alpine（端口 3000 映射到 80）
#   - 前端挂载 ui/dist 静态文件 + deploy/nginx/nginx.conf 配置
#   - nginx 反向代理 /api 到后端
#

set -e

IMAGE_TAG="${1:-latest}"
BACKEND_CONTAINER="panjia-console"
FRONTEND_CONTAINER="panjia-console-web"
PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"

echo "=========================================="
echo "  启动 panjia-console（本地开发）"
echo "  镜像标签：$IMAGE_TAG"
echo "=========================================="

# 加载 .env
if [ -f "$PROJECT_DIR/.env" ]; then
    set -a
    . "$PROJECT_DIR/.env"
    set +a
    echo "[INFO] 已加载 .env 配置"
else
    echo "[WARN] 未找到 .env 文件，使用默认值"
fi

# 检查 JKS
JKS_FILE="$PROJECT_DIR/script/keys/panjia-license.jks"
if [ ! -f "$JKS_FILE" ]; then
    echo "[ERROR] 未找到 JKS 文件：$JKS_FILE"
    echo "        请先执行：sh bin/gen_keypair.sh <密码>"
    exit 1
fi

# 检查前端静态文件
FRONTEND_DIST="$PROJECT_DIR/ui/dist"
NGINX_CONF="$PROJECT_DIR/deploy/nginx/nginx.conf"
if [ ! -d "$FRONTEND_DIST" ]; then
    echo "[WARN] 未找到前端构建文件 ui/dist/"
    echo "       请先执行：sh bin/build.sh --frontend"
    echo "       跳过前端容器，仅启动后端"
    START_FRONTEND=false
else
    START_FRONTEND=true
fi

# 默认值
DB_URL="${SPRING_DATASOURCE_URL:-jdbc:postgresql://host.docker.internal:5432/postgres?TimeZone=Asia/Shanghai}"
DB_USERNAME="${SPRING_DATASOURCE_USERNAME:-postgres}"
DB_PASSWORD="${SPRING_DATASOURCE_PASSWORD:-postgres}"
JKS_PASSWORD="${PANJIA_JKS_PASSWORD:-devpass123456}"
KEY_PASSWORD="${PANJIA_KEY_PASSWORD:-devpass123456}"
OPERATOR_NAME="${PANJIA_OPERATOR_NAME:-admin}"
BACKEND_PORT="${PANJIA_BACKEND_PORT:-8081}"
FRONTEND_PORT="${PANJIA_FRONTEND_PORT:-3000}"

# ---- 启动后端 ----
echo ""
echo "[1/2] 后端容器..."
if docker ps -q --filter "name=$BACKEND_CONTAINER" | grep -q .; then
    echo "  已在运行"
elif docker ps -a -q --filter "name=$BACKEND_CONTAINER" | grep -q .; then
    docker start "$BACKEND_CONTAINER"
    echo "  ✓ 已启动"
else
    docker run -d \
        --name "$BACKEND_CONTAINER" \
        -p "${BACKEND_PORT}:8080" \
        -v "$JKS_FILE:/app/keys/panjia-license.jks:ro" \
        -v "$PROJECT_DIR/backups:/app/backups" \
        -e "SPRING_DATASOURCE_URL=$DB_URL" \
        -e "SPRING_DATASOURCE_USERNAME=$DB_USERNAME" \
        -e "SPRING_DATASOURCE_PASSWORD=$DB_PASSWORD" \
        -e "PANJIA_JKS_PASSWORD=$JKS_PASSWORD" \
        -e "PANJIA_KEY_PASSWORD=$KEY_PASSWORD" \
        -e "PANJIA_OPERATOR_NAME=$OPERATOR_NAME" \
        --restart unless-stopped \
        "panjia-console:${IMAGE_TAG}"
    echo "  ✓ 已创建并启动"
fi

# ---- 启动前端 ----
if [ "$START_FRONTEND" = true ]; then
    echo ""
    echo "[2/2] 前端容器（nginx:stable-alpine）..."
    # 先删除旧容器（配置可能变了）
    docker rm -f "$FRONTEND_CONTAINER" 2>/dev/null || true
    docker run -d \
        --name "$FRONTEND_CONTAINER" \
        -p "${FRONTEND_PORT}:80" \
        --link "$BACKEND_CONTAINER:console" \
        -v "$NGINX_CONF:/etc/nginx/nginx.conf:ro" \
        -v "$FRONTEND_DIST:/var/www/panjia-console:ro" \
        --restart unless-stopped \
        nginx:stable-alpine
    echo "  ✓ 已创建并启动"
fi

echo ""
echo "=========================================="
echo "  ✓ 启动完成"
if [ "$START_FRONTEND" = true ]; then
    echo "  前端地址：http://localhost:${FRONTEND_PORT}"
fi
echo "  后端地址：http://localhost:${BACKEND_PORT}"
echo "  查看日志：sh bin/logs.sh"
echo "=========================================="
