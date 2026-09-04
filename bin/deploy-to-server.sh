#!/bin/bash
# ============================================================================
# 一键发布到远程服务器（前端 + 后端）
#
# 功能：
#   1. 本地构建 Docker 镜像（前后端）
#   2. 导出镜像为 tar 文件
#   3. 通过 SSH + SCP 上传到服务器
#   4. 服务器端导入镜像并重启服务
#
# 用法：
#   sh bin/deploy-to-server.sh <服务器地址> [镜像标签] [SSH用户] [部署目录]
#
# 示例：
#   sh bin/deploy-to-server.sh 192.168.1.100
#   sh bin/deploy-to-server.sh 192.168.1.100 v1.0.0 root /opt/panjia-console
#
# 只发布后端：
#   sh bin/deploy-to-server.sh 192.168.1.100 latest -- --backend
#
# 只发布前端：
#   sh bin/deploy-to-server.sh 192.168.1.100 latest -- --frontend
#
# 前提：
#   - 服务器已执行过 server-init.sh 初始化
#   - 本地已配置 SSH 免密登录（或能输入密码）
#   - 服务器 /opt/panjia-console/ 下已有 docker-compose.yml 和 .env
# ============================================================================

set -e

# ---- 参数 ----
SERVER_ADDR="${1:?用法：sh bin/deploy-to-server.sh <服务器地址> [镜像标签] [SSH用户] [部署目录]}"
IMAGE_TAG="${2:-latest}"
SSH_USER="${3:-root}"
INSTALL_DIR="${4:-/opt/panjia-console}"

# 解析 --backend / --frontend 参数
DEPLOY_BACKEND=true
DEPLOY_FRONTEND=true
for arg in "$@"; do
    if [ "$arg" = "--backend" ]; then
        DEPLOY_FRONTEND=false
    elif [ "$arg" = "--frontend" ]; then
        DEPLOY_BACKEND=false
    fi
done

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
BACKEND_IMAGE="panjia-console:${IMAGE_TAG}"
FRONTEND_IMAGE="panjia-console-web:${IMAGE_TAG}"
BACKEND_TAR="panjia-console-${IMAGE_TAG}.tar"
FRONTEND_TAR="panjia-console-web-${IMAGE_TAG}.tar"
TMP_DIR="/tmp/panjia-deploy-$$"

echo "=========================================="
echo "  发布到服务器：$SSH_USER@$SERVER_ADDR"
echo "  镜像标签：$IMAGE_TAG"
echo "  部署目录：$INSTALL_DIR"
if [ "$DEPLOY_BACKEND" = true ]; then
    echo "  发布后端：$BACKEND_IMAGE"
fi
if [ "$DEPLOY_FRONTEND" = true ]; then
    echo "  发布前端：$FRONTEND_IMAGE"
fi
echo "=========================================="

# ---- 1. 构建镜像 ----
echo ""
echo ">>> 步骤 1/5：构建镜像"
if [ "$DEPLOY_BACKEND" = true ] && [ "$DEPLOY_FRONTEND" = true ]; then
    sh "$PROJECT_DIR/bin/build.sh" "$IMAGE_TAG"
elif [ "$DEPLOY_BACKEND" = true ]; then
    sh "$PROJECT_DIR/bin/build.sh" "$IMAGE_TAG" --backend
elif [ "$DEPLOY_FRONTEND" = true ]; then
    sh "$PROJECT_DIR/bin/build.sh" "$IMAGE_TAG" --frontend
fi

# ---- 2. 导出镜像 ----
echo ""
echo ">>> 步骤 2/5：导出镜像"
mkdir -p "$TMP_DIR"

if [ "$DEPLOY_BACKEND" = true ]; then
    docker save -o "$TMP_DIR/$BACKEND_TAR" "$BACKEND_IMAGE"
    echo "  ✓ 后端镜像已导出：$(du -h "$TMP_DIR/$BACKEND_TAR" | cut -f1)"
fi

if [ "$DEPLOY_FRONTEND" = true ]; then
    docker save -o "$TMP_DIR/$FRONTEND_TAR" "$FRONTEND_IMAGE"
    echo "  ✓ 前端镜像已导出：$(du -h "$TMP_DIR/$FRONTEND_TAR" | cut -f1)"
fi

# ---- 3. 上传镜像 ----
echo ""
echo ">>> 步骤 3/5：上传镜像到服务器"
if [ "$DEPLOY_BACKEND" = true ]; then
    scp "$TMP_DIR/$BACKEND_TAR" "${SSH_USER}@${SERVER_ADDR}:${INSTALL_DIR}/"
    echo "  ✓ 后端镜像上传完成"
fi
if [ "$DEPLOY_FRONTEND" = true ]; then
    scp "$TMP_DIR/$FRONTEND_TAR" "${SSH_USER}@${SERVER_ADDR}:${INSTALL_DIR}/"
    echo "  ✓ 前端镜像上传完成"
fi

# ---- 4. 服务器端导入镜像 + 重启 ----
echo ""
echo ">>> 步骤 4/5：服务器端导入并重启"
ssh "${SSH_USER}@${SERVER_ADDR}" << EOF
set -e
cd "$INSTALL_DIR"

# 导入镜像
if [ "$DEPLOY_BACKEND" = true ]; then
    echo "[服务器] 导入后端镜像..."
    docker load -i "$BACKEND_TAR"
    rm -f "$BACKEND_TAR"
fi

if [ "$DEPLOY_FRONTEND" = true ]; then
    echo "[服务器] 导入前端镜像..."
    docker load -i "$FRONTEND_TAR"
    rm -f "$FRONTEND_TAR"
fi

# 更新 IMAGE_TAG
echo "[服务器] 更新 .env 中的 IMAGE_TAG..."
if grep -q '^IMAGE_TAG=' .env; then
    sed -i 's/^IMAGE_TAG=.*/IMAGE_TAG=${IMAGE_TAG}/' .env
else
    echo "IMAGE_TAG=${IMAGE_TAG}" >> .env
fi

# 重启服务
echo "[服务器] 重启服务..."
docker compose up -d

echo "[服务器] 等待服务启动..."
sleep 5
docker compose ps
EOF

# ---- 5. 清理临时文件 ----
echo ""
echo ">>> 步骤 5/5：清理本地临时文件"
rm -rf "$TMP_DIR"
echo "  ✓ 清理完成"

echo ""
echo "=========================================="
echo "  ✓ 发布完成"
echo "=========================================="
echo ""
echo "访问地址：http://$SERVER_ADDR"
echo "查看日志：ssh ${SSH_USER}@${SERVER_ADDR} 'cd $INSTALL_DIR && docker compose logs -f'"
echo "=========================================="
