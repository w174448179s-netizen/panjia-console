#!/bin/bash
# ============================================================================
# 只推送后端（不动前端、不动数据库、保留 .env）
#
# 适用场景：改完 Java 代码、Dockerfile 或 docker-compose.yml 后快速发布,
#          跳过 setup-server.sh 的全流程检测,只更新后端容器
#
# 用法：
#   sh bin/push-backend.sh <服务器IP> <SSH用户> [镜像标签]
#
# 示例：
#   sh bin/push-backend.sh 118.24.77.11 ubuntu
#   sh bin/push-backend.sh 118.24.77.11 ubuntu v2
#
# 前提：服务器已经跑过 setup-server.sh 一次(INSTALL_DIR + docker 已就绪)
# 不会动：nginx 容器、前端 web/dist、postgres 数据、.env、JKS
# 会改：panjia-console:<tag> 镜像 + docker-compose.yml（如果本地有变更）+ 重启 console 容器
# ============================================================================

set -e

# ---- 参数 ----
SERVER_ADDR="${1:?用法：sh bin/push-backend.sh <服务器IP> <SSH用户> [镜像标签]}"
SSH_USER="${2:?请提供 SSH 用户名（如 ubuntu）}"
IMAGE_TAG="${3:-v1}"

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
INSTALL_DIR="/opt/panjia-console"
CACHE_DIR="$PROJECT_DIR/.workbuddy/cache"
BACKEND_IMAGE="panjia-console:${IMAGE_TAG}"
BACKEND_TAR="panjia-console-${IMAGE_TAG}.tar"

# 远程 staging 路径(与 setup-server.sh 同约定)
if [ "$SSH_USER" = "root" ]; then
    REMOTE_STAGING="/root/.cache/panjia-staging"
else
    REMOTE_STAGING="/home/$SSH_USER/.cache/panjia-staging"
fi

echo "=========================================="
echo "  后端推送"
echo "  服务器：    $SSH_USER@$SERVER_ADDR"
echo "  后端镜像：  $BACKEND_IMAGE"
echo "  部署目录：  $INSTALL_DIR"
echo "=========================================="

# ---- 1. SSH / sudo 检查 + 本地前置 ----
echo ""
echo ">>> 步骤 1/4：本地准备"
if ! ssh -o ConnectTimeout=10 -o BatchMode=yes "${SSH_USER}@${SERVER_ADDR}" "echo ok" &>/dev/null; then
    echo "  [ERROR] 无法 SSH 连接到 $SSH_USER@$SERVER_ADDR"
    exit 1
fi
REMOTE_BASH="bash -s"
if [ "$SSH_USER" != "root" ]; then
    if ssh -o ConnectTimeout=10 -o BatchMode=yes "${SSH_USER}@${SERVER_ADDR}" "sudo -n true" 2>/dev/null; then
        REMOTE_BASH="sudo bash -s"
        echo "  ✓ sudo 免密可用"
    else
        echo "  [ERROR] 用户 ${SSH_USER} 无法免密 sudo"
        exit 1
    fi
fi

# 总是强制重新构建: 先删除旧镜像,避免 docker build 缓存命中导致新层没生效
if docker image inspect "$BACKEND_IMAGE" >/dev/null 2>&1; then
    echo "  发现旧镜像 $BACKEND_IMAGE,删除以强制 rebuild..."
    docker rmi "$BACKEND_IMAGE" 2>&1 | sed 's/^/    /'
fi
echo "  触发后端构建..."
sh "$PROJECT_DIR/bin/build.sh" "$IMAGE_TAG" --backend
echo "  ✓ 镜像 $BACKEND_IMAGE 就绪"

# ---- 2. docker save + 上传 ----
echo ""
echo ">>> 步骤 2/4：导出并上传镜像"
mkdir -p "$CACHE_DIR"
docker save -o "$CACHE_DIR/$BACKEND_TAR" "$BACKEND_IMAGE"
echo "  ✓ 后端镜像已导出：$(du -h "$CACHE_DIR/$BACKEND_TAR" | cut -f1)"

scp "$CACHE_DIR/$BACKEND_TAR" "${SSH_USER}@${SERVER_ADDR}:${REMOTE_STAGING}/"
echo "  ✓ 后端 tar 已上传到 $REMOTE_STAGING/"

# 同时带上最新的 docker-compose.yml(代码改动常伴随 compose 改动)
scp "$PROJECT_DIR/deploy/server/docker-compose.yml" "${SSH_USER}@${SERVER_ADDR}:${REMOTE_STAGING}/"
echo "  ✓ docker-compose.yml 已上传"

# ---- 3. 服务器：导入 + 重启 console 容器 ----
echo ""
echo ">>> 步骤 3/4：服务器导入镜像并重启 console"
ssh -o ConnectTimeout=10 -o BatchMode=yes -o ServerAliveInterval=30 -o ServerAliveCountMax=3 "${SSH_USER}@${SERVER_ADDR}" "$REMOTE_BASH" << REMOTE_DEPLOY
set -e
INSTALL_DIR="$INSTALL_DIR"
REMOTE_STAGING="$REMOTE_STAGING"
BACKEND_TAR="$BACKEND_TAR"
IMAGE_TAG="$IMAGE_TAG"

cd "\$INSTALL_DIR"

# 覆盖 docker-compose.yml(本地新版本)
mv -f "\$REMOTE_STAGING/docker-compose.yml" ./
echo "[服务器] ✓ docker-compose.yml 已更新"

# 导入镜像(已存在的 image layer 会秒过)
echo "[服务器] 导入镜像 panjia-console:\$IMAGE_TAG ..."
docker load -i "\$REMOTE_STAGING/\$BACKEND_TAR"
echo "[服务器] ✓ 镜像已导入"
rm -f "\$REMOTE_STAGING/\$BACKEND_TAR"

# 重建并启动 console 容器,带 --wait 等 healthcheck 通过
# 只重建 console(不是 up -d 全量),避免影响 postgres / nginx
# --force-recreate: 新镜像必须强制重建容器才能让 java 进程加载新代码
echo "[服务器] 重启 console 容器(--wait 等健康检查通过)..."
docker compose up -d --force-recreate --wait --wait-timeout 300 console

echo "[服务器] 当前容器状态："
docker compose ps
REMOTE_DEPLOY

# ---- 4. 健康检查 ----
# docker compose --wait 只保证容器端口可达,Sprint Boot + JPA 还要几秒 warm up
echo ""
echo ">>> 步骤 4/4：验证后端 API"
SUCC=0
for i in $(seq 1 15); do
    HTTP_CODE=$(curl -s -m 5 -o /dev/null -w "%{http_code}" "http://${SERVER_ADDR}/api/v1/dashboard/stats" 2>/dev/null || echo "000")
    if [ "$HTTP_CODE" = "200" ]; then
        echo "  ✓ 后端 API 正常(第 $i/15 次)"
        SUCC=1
        break
    fi
    sleep 2
done
[ "$SUCC" = "0" ] && echo "  [WARN] 重试 15 次仍返回 $HTTP_CODE(查看日志:ssh $SSH_USER@$SERVER_ADDR 'cd $INSTALL_DIR && ./logs.sh')"

echo ""
echo "=========================================="
echo "  ✓ 后端推送完成"
echo "=========================================="