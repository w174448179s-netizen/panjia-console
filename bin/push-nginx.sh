#!/bin/bash
# ============================================================================
# 推送 nginx 配置到服务器并安全生效
#
# 做的事：
#   1. 上传 deploy/nginx/nginx.conf + .htpasswd + docker-compose.yml
#   2. 先备份服务器上的旧 nginx.conf
#   3. 重建 web 容器（compose 挂载变化时生效，如新增 .htpasswd 挂载）
#   4. 替换 nginx.conf → nginx -t 校验 → 通过才 reload，失败自动回滚
#
# 用法：
#   sh bin/push-nginx.sh                          # 从 bin/server.env 读取服务器信息（推荐）
#   sh bin/push-nginx.sh <服务器IP> <SSH用户>      # 显式指定（优先级更高）
# 示例：
#   sh bin/push-nginx.sh 118.24.77.11 ubuntu
#
# 注意：
#   - 家宽 IP 变动后：改 deploy/nginx/nginx.conf 里的 allow 行，重跑本脚本
#   - 改 Basic Auth 密码：sh bin/rotate-auth-password.sh 一键轮换
# ============================================================================

set -e

# 服务器地址：优先级 = 命令行参数 > bin/server.env（gitignored）
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

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
INSTALL_DIR="${INSTALL_DIR:-/opt/panjia-console}"   # 可在 bin/server.env 配置

if [ "$SSH_USER" = "root" ]; then
    REMOTE_STAGING="/root/.cache/panjia-staging"
else
    REMOTE_STAGING="/home/$SSH_USER/.cache/panjia-staging"
fi

echo "=========================================="
echo "  推送 nginx 配置到：$SSH_USER@$SERVER_ADDR"
echo "  目标目录：$INSTALL_DIR/nginx/"
echo "=========================================="

# ---- 1. 连通性检查 ----
if ! ssh -o ConnectTimeout=10 -o BatchMode=yes "${SSH_USER}@${SERVER_ADDR}" "echo ok" &>/dev/null; then
    echo "[ERROR] 无法 SSH 连接到 $SSH_USER@$SERVER_ADDR"
    exit 1
fi
REMOTE_BASH="bash -s"
if [ "$SSH_USER" != "root" ]; then
    if ssh -o ConnectTimeout=10 -o BatchMode=yes "${SSH_USER}@${SERVER_ADDR}" "sudo -n true" 2>/dev/null; then
        REMOTE_BASH="sudo bash -s"
        echo "✓ sudo 免密可用"
    else
        echo "[ERROR] 用户 ${SSH_USER} 无法免密 sudo"
        exit 1
    fi
fi

# ---- 2. 上传到 staging ----
ssh -o BatchMode=yes "${SSH_USER}@${SERVER_ADDR}" "mkdir -p '$REMOTE_STAGING'"
scp "$PROJECT_DIR/deploy/nginx/nginx.conf" \
    "$PROJECT_DIR/deploy/nginx/.htpasswd" \
    "$PROJECT_DIR/deploy/server/docker-compose.yml" \
    "${SSH_USER}@${SERVER_ADDR}:${REMOTE_STAGING}/"
echo "✓ 3 个文件已上传（nginx.conf / .htpasswd / docker-compose.yml）"

# ---- 3. 服务器端：备份 → 重建 web 容器 → 换配置 → 校验 → reload ----
ssh -o BatchMode=yes "${SSH_USER}@${SERVER_ADDR}" "$REMOTE_BASH" << REMOTE_EXEC
set -e
INSTALL_DIR="$INSTALL_DIR"
REMOTE_STAGING="$REMOTE_STAGING"
TS=\$(date +%Y%m%d-%H%M%S)

cd "\$INSTALL_DIR"

# 备份旧配置
cp -f nginx/nginx.conf "nginx/nginx.conf.bak-\$TS" 2>/dev/null || true
echo "[服务器] ✓ 旧配置已备份：nginx/nginx.conf.bak-\$TS"

# .htpasswd / compose 用 cp（保持 inode，容器内挂载立即可见）
# 注意：必须 644——容器里 nginx worker 以 nginx 用户跑，640 会读不了文件，
# Basic Auth 密码校验时直接 500（踩过坑）
cp -f "\$REMOTE_STAGING/.htpasswd" nginx/.htpasswd
chmod 644 nginx/.htpasswd
cp -f "\$REMOTE_STAGING/docker-compose.yml" docker-compose.yml

# 重建 web 容器（compose 挂载有变化才会真正重建，否则幂等无操作）
docker compose up -d web
echo "[服务器] ✓ web 容器已就绪（.htpasswd 挂载生效）"

# 替换 nginx.conf（cp 保持 inode）
cp -f "\$REMOTE_STAGING/nginx.conf" nginx/nginx.conf

# 校验：失败则回滚旧配置
if docker exec panjia-console-web nginx -t 2>&1; then
    docker exec panjia-console-web nginx -s reload
    echo "[服务器] ✓ nginx 配置校验通过并已 reload"
else
    echo "[服务器] [ERROR] nginx -t 校验失败，回滚旧配置..."
    cp -f "nginx/nginx.conf.bak-\$TS" nginx/nginx.conf
    docker exec panjia-console-web nginx -t && docker exec panjia-console-web nginx -s reload
    echo "[服务器] 已回滚，请检查本地 nginx.conf 语法"
    exit 1
fi

rm -f "\$REMOTE_STAGING/nginx.conf" "\$REMOTE_STAGING/.htpasswd" "\$REMOTE_STAGING/docker-compose.yml"
docker compose ps web
REMOTE_EXEC

echo ""
echo "=========================================="
echo "  ✓ nginx 配置推送完成"
echo "=========================================="
