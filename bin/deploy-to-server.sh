#!/bin/bash
# ============================================================================
# 一键发布到远程服务器（前端 + 后端）
#
# 以 push-backend.sh / push-frontend.sh 为标准，合并为同时发布前后端。
# 后端：docker build → save → scp → load → recreate console (--wait)
# 前端：build dist(若需要) → rsync 增量同步 → nginx reload
#
# 用法：
#   sh bin/deploy-to-server.sh                     # 服务器信息读 bin/server.env（推荐）
#   sh bin/deploy-to-server.sh [镜像标签] [flags]
#   sh bin/deploy-to-server.sh <服务器IP> <SSH用户> [镜像标签]   # 旧写法，兼容
#
# 示例：
#   sh bin/deploy-to-server.sh
#   sh bin/deploy-to-server.sh v2
#
# 只发布后端：
#   sh bin/deploy-to-server.sh v2 --backend
#
# 只发布前端：
#   sh bin/deploy-to-server.sh --frontend
#
# 前提：服务器已经跑过 setup-server.sh 一次（INSTALL_DIR + docker 已就绪）
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
INSTALL_DIR="${INSTALL_DIR:-/opt/panjia-console}"   # 可在 bin/server.env 配置
CACHE_DIR="$PROJECT_DIR/.workbuddy/cache"
BACKEND_IMAGE="panjia-console:${IMAGE_TAG}"
BACKEND_TAR="panjia-console-${IMAGE_TAG}.tar"

# 远程 staging 路径（与 setup-server.sh / push-backend.sh 同约定）
if [ "$SSH_USER" = "root" ]; then
    REMOTE_STAGING="/root/.cache/panjia-staging"
else
    REMOTE_STAGING="/home/$SSH_USER/.cache/panjia-staging"
fi

echo "=========================================="
echo "  发布到服务器：$SSH_USER@$SERVER_ADDR"
echo "  镜像标签：   $IMAGE_TAG"
echo "  部署目录：   $INSTALL_DIR"
if [ "$DEPLOY_BACKEND" = true ]; then
    echo "  发布后端：   $BACKEND_IMAGE"
fi
if [ "$DEPLOY_FRONTEND" = true ]; then
    echo "  发布前端：   rsync ui/dist/ → nginx reload"
fi
echo "=========================================="

# ---- 1. SSH / sudo 检查 ----
echo ""
echo ">>> 步骤 1/5：本地准备"
if ! ssh -o ConnectTimeout=10 -o BatchMode=yes "${SSH_USER}@${SERVER_ADDR}" "echo ok" &>/dev/null; then
    echo "  [ERROR] 无法 SSH 连接到 $SSH_USER@$SERVER_ADDR"
    echo "  请先执行：ssh-copy-id $SSH_USER@$SERVER_ADDR"
    exit 1
fi
REMOTE_BASH="bash -s"
if [ "$SSH_USER" != "root" ]; then
    if ssh -o ConnectTimeout=10 -o BatchMode=yes "${SSH_USER}@${SERVER_ADDR}" "sudo -n true" 2>/dev/null; then
        REMOTE_BASH="sudo bash -s"
        echo "  ✓ sudo 免密可用"
    else
        echo "  [ERROR] 用户 ${SSH_USER} 无法免密 sudo"
        echo "  请先在服务器配置 NOPASSWD"
        exit 1
    fi
else
    echo "  ✓ root 用户直接登录"
fi

# ---- 2. 构建 ----
echo ""
echo ">>> 步骤 2/5：构建"

if [ "$DEPLOY_BACKEND" = true ]; then
    # 强制重新构建：先删旧镜像，避免 docker build 缓存命中导致新层没生效
    if docker image inspect "$BACKEND_IMAGE" >/dev/null 2>&1; then
        echo "  发现旧镜像 $BACKEND_IMAGE，删除以强制 rebuild..."
        docker rmi "$BACKEND_IMAGE" 2>&1 | sed 's/^/    /'
    fi
    echo "  触发后端构建..."
    sh "$PROJECT_DIR/bin/build.sh" "$IMAGE_TAG" --backend
    echo "  ✓ 后端镜像 $BACKEND_IMAGE 就绪"
fi

if [ "$DEPLOY_FRONTEND" = true ]; then
    # dist 缺失 或 源码比 dist 新 → 重新构建
    if [ ! -f "$PROJECT_DIR/ui/dist/index.html" ] || \
       [ -n "$(find "$PROJECT_DIR/ui/src" -newer "$PROJECT_DIR/ui/dist/index.html" -print -quit 2>/dev/null)" ]; then
        echo "  ui/dist/ 缺失或源码有更新，触发前端构建..."
        sh "$PROJECT_DIR/bin/build.sh" "$IMAGE_TAG" --frontend
    else
        echo "  ✓ ui/dist/ 已是最新"
    fi
fi

# ---- 3. 上传 ----
echo ""
echo ">>> 步骤 3/5：上传文件到服务器"

if [ "$DEPLOY_BACKEND" = true ]; then
    mkdir -p "$CACHE_DIR"
    docker save -o "$CACHE_DIR/$BACKEND_TAR" "$BACKEND_IMAGE"
    echo "  ✓ 后端镜像已导出：$(du -h "$CACHE_DIR/$BACKEND_TAR" | cut -f1)"

    # 确保远程 staging 目录存在
    ssh -o ConnectTimeout=10 -o BatchMode=yes "${SSH_USER}@${SERVER_ADDR}" \
        "mkdir -p '$REMOTE_STAGING'" 2>/dev/null || \
    ssh -o ConnectTimeout=10 -o BatchMode=yes "${SSH_USER}@${SERVER_ADDR}" \
        "sudo mkdir -p '$REMOTE_STAGING' && sudo chown \$USER:\$USER '$REMOTE_STAGING'"

    scp "$CACHE_DIR/$BACKEND_TAR" "${SSH_USER}@${SERVER_ADDR}:${REMOTE_STAGING}/"
    echo "  ✓ 后端 tar 已上传"

    # 同时带上最新的 docker-compose.yml（代码改动常伴随 compose 改动）
    scp "$PROJECT_DIR/deploy/server/docker-compose.yml" "${SSH_USER}@${SERVER_ADDR}:${REMOTE_STAGING}/"
    echo "  ✓ docker-compose.yml 已上传"
fi

if [ "$DEPLOY_FRONTEND" = true ]; then
    # rsync 增量同步 + --delete 清理旧文件
    # --rsync-path="sudo rsync": 写到 /opt/panjia-console/web/dist/ 需要 sudo
    rsync -az --delete --rsync-path="sudo rsync" \
        "$PROJECT_DIR/ui/dist/" \
        "${SSH_USER}@${SERVER_ADDR}:${INSTALL_DIR}/web/dist/"
    echo "  ✓ 前端文件已同步"
fi

# ---- 4. 服务器端导入 + 重启 ----
echo ""
echo ">>> 步骤 4/5：服务器导入并重启"

if [ "$DEPLOY_BACKEND" = true ]; then
    ssh -o ConnectTimeout=10 -o BatchMode=yes -o ServerAliveInterval=30 -o ServerAliveCountMax=3 \
        "${SSH_USER}@${SERVER_ADDR}" "$REMOTE_BASH" << REMOTE_DEPLOY
set -e
INSTALL_DIR="$INSTALL_DIR"
REMOTE_STAGING="$REMOTE_STAGING"
BACKEND_TAR="$BACKEND_TAR"
IMAGE_TAG="$IMAGE_TAG"

cd "\$INSTALL_DIR"

# 覆盖 docker-compose.yml（本地新版本）
mv -f "\$REMOTE_STAGING/docker-compose.yml" ./
echo "[服务器] ✓ docker-compose.yml 已更新"

# 导入镜像（已存在的 image layer 会秒过）
echo "[服务器] 导入镜像 panjia-console:\$IMAGE_TAG ..."
docker load -i "\$REMOTE_STAGING/\$BACKEND_TAR"
echo "[服务器] ✓ 镜像已导入"
rm -f "\$REMOTE_STAGING/\$BACKEND_TAR"

# 重建并启动 console 容器，带 --wait 等 healthcheck 通过
# 只重建 console（不是 up -d 全量），避免影响 postgres / nginx
# --force-recreate: 新镜像必须强制重建容器才能让 java 进程加载新代码
echo "[服务器] 重启 console 容器（--wait 等健康检查通过）..."
docker compose up -d --force-recreate --wait --wait-timeout 300 console

echo "[服务器] 当前容器状态："
docker compose ps
REMOTE_DEPLOY

    echo "  ✓ 后端已重启"
fi

if [ "$DEPLOY_FRONTEND" = true ]; then
    # nginx reload 让 worker 进程重新打开被替换的文件
    ssh -o ConnectTimeout=10 -o BatchMode=yes "${SSH_USER}@${SERVER_ADDR}" \
        "sudo docker exec panjia-console-web nginx -s reload" \
        2>/dev/null && echo "  ✓ nginx reloaded" || echo "  [WARN] nginx reload 失败（可能容器未运行）"
fi

# ---- 5. 健康检查 ----
echo ""
echo ">>> 步骤 5/5：验证服务"
SUCC=0
HTTP_CODE="000"
for i in $(seq 1 15); do
    HTTP_CODE=$(curl -s -m 5 -o /dev/null -w "%{http_code}" "http://${SERVER_ADDR}/api/v1/dashboard/stats" 2>/dev/null || echo "000")
    if [ "$HTTP_CODE" = "200" ]; then
        echo "  ✓ 后端 API 正常（第 $i/15 次）"
        SUCC=1
        break
    fi
    sleep 2
done
if [ "$SUCC" = "0" ]; then
    echo "  [WARN] 后端 API 重试 15 次仍返回 $HTTP_CODE"
    echo "  查看日志：ssh $SSH_USER@$SERVER_ADDR 'cd $INSTALL_DIR && ./logs.sh'"
fi

echo ""
echo "=========================================="
echo "  ✓ 发布完成"
echo "=========================================="
echo ""
echo "  访问地址：http://$SERVER_ADDR"
echo "  查看日志：ssh $SSH_USER@$SERVER_ADDR 'cd $INSTALL_DIR && ./logs.sh'"
echo "  重启服务：ssh $SSH_USER@$SERVER_ADDR 'cd $INSTALL_DIR && ./restart.sh'"
echo "=========================================="
