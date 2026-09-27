#!/bin/bash
# ============================================================================
# 盘家 preview 部署（多成员版）—— 从本机 Mac 执行
#
# 作用：
#   把 panjia-ui 的构建产物发布到云服务器，由服务器 nginx 直接对外提供静态
#   资源；API 请求经 frps 的 api 隧道（宿主机 8080+N → 成员 N 本机 8080）回后端。
#
#   前端产物全员共享同一份 dist；各成员差异只在 nginx 站点反代的 api 隧道口。
#
# 端口约定（第 N 号成员）：
#   站点（客户访问）   http://panjia.icu:3100+N    （nginx 静态直出 + API 反代）
#   api 隧道           8080+N  →  成员本机 8080    （frpc）
#   备用直连隧道       3000+N  →  成员本机 80     （frpc，慢，仅备用）
#
# 用法：
#   sh bin/frp/deploy-preview.sh [成员号] [--build]
#
#     成员号     0~99，默认 0（你自己）。新成员接入：先 pack-frpc.sh 打包，
#                再跑 sh bin/frp/deploy-preview.sh <N> 生成其站点
#     --build    同步前先在 panjia-ui 执行 pnpm build（默认只同步已有 dist）
#
# 幂等性：可重复执行
#   - dist 用 rsync 增量同步（只传变化文件）
#   - member<N>.conf 每次覆盖 + nginx reload（无损）
#   - 容器仅一个（panjia-preview，-p 3100-3199 整段映射 + conf/dist 目录挂载），
#     新成员只加 conf 文件 + reload，不重建容器；挂载/端口方式不符时自动重建
#
# 服务器落点：
#   /opt/panjia-preview/conf/member<N>.conf   各成员 nginx 站点
#   /opt/panjia-preview/dist/                 共享前端构建产物
#   容器：panjia-preview（nginx:stable-alpine，-p 3100-3199:3100-3199）
#
# 前置条件：
#   SSH 免密 + sudo NOPASSWD（同其他 bin/*.sh）；
#   腾讯云防火墙放行 3100-3199（bin/frp/firewall-rules.csv）
# ============================================================================

set -e

MEMBER=0
DO_BUILD=0
for arg in "$@"; do
    case "$arg" in
        --build) DO_BUILD=1 ;;
        --help|-h)
            awk 'NR==1{next} /^#/{sub(/^# ?/,""); print; next} {exit}' "$0"
            exit 0 ;;
        ''|*[!0-9]*) echo "[ERROR] 未知参数：$arg（成员号须为 0~99 的数字）"; exit 1 ;;
        *) MEMBER="$arg" ;;
    esac
done
[ "$MEMBER" -le 99 ] || { echo "[ERROR] 成员号须为 0~99"; exit 1; }

WEB_PORT=$((3100 + MEMBER))
API_PORT=$((8080 + MEMBER))

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
UI_DIR="${PANJIA_UI_DIR:-$(cd "$SCRIPT_DIR/../../.." && pwd)/panjia-ui}"
DIST_DIR="$UI_DIR/dist"

# ---- 服务器配置（优先级：环境变量 > bin/server.env，与 bin/*.sh 一致）----
ENV_FILE="$(cd "$SCRIPT_DIR/.." && pwd)/server.env"
if [ -f "$ENV_FILE" ]; then
    _O_IP="${SERVER_IP:-}"; _O_USER="${SERVER_USER:-}"
    . "$ENV_FILE"
    [ -n "$_O_IP" ]   && SERVER_IP="$_O_IP"
    [ -n "$_O_USER" ] && SERVER_USER="$_O_USER"
fi
SERVER_ADDR="${SERVER_ADDR:-${SERVER_IP:?请创建 bin/server.env（模板见 bin/server.env.example）}}"
SSH_USER="${SSH_USER:-${SERVER_USER:-ubuntu}}"
SSH_TARGET="$SSH_USER@$SERVER_ADDR"

echo "=========================================="
echo "  部署盘家 preview 站点（成员 $MEMBER 号）"
echo "  站点端口：$WEB_PORT   API 隧道：$API_PORT"
echo "  前端目录：$UI_DIR"
echo "  目标服务器：$SSH_TARGET"
echo "=========================================="

# ---- SSH 预检 ----
ssh -o ConnectTimeout=10 -o BatchMode=true "$SSH_TARGET" "echo ok" > /dev/null \
    || { echo "[ERROR] SSH 连不上 $SSH_TARGET，请确认免密登录已配置"; exit 1; }

# ---- 构建（可选）----
if [ "$DO_BUILD" = 1 ]; then
    command -v pnpm > /dev/null || { echo "[ERROR] 未找到 pnpm，无法 --build"; exit 1; }
    echo ""
    echo "[1/4] 构建前端（pnpm build）..."
    (cd "$UI_DIR" && pnpm build)
else
    echo ""
    echo "[1/4] 跳过构建（--build 可先构建）"
fi

[ -f "$DIST_DIR/index.html" ] \
    || { echo "[ERROR] 未找到 $DIST_DIR/index.html，请先执行：sh bin/frp/deploy-preview.sh --build"; exit 1; }

command -v rsync > /dev/null || { echo "[ERROR] 本机未找到 rsync"; exit 1; }

# ---- 生成成员站点 conf 并上传 ----
echo ""
echo "[2/4] 生成成员 $MEMBER 的 nginx 站点（listen $WEB_PORT → api $API_PORT）..."
TMP_CONF="$(mktemp)"
trap 'rm -f "$TMP_CONF"' EXIT
sed -e "s/__WEB_PORT__/$WEB_PORT/g" -e "s/__API_PORT__/$API_PORT/g" \
    "$SCRIPT_DIR/nginx-preview.conf" > "$TMP_CONF"

ssh "$SSH_TARGET" "sudo mkdir -p /opt/panjia-preview/conf /opt/panjia-preview/dist && sudo chown -R $SSH_USER /opt/panjia-preview"
scp -q "$TMP_CONF" "$SSH_TARGET:/opt/panjia-preview/conf/member$MEMBER.conf"
# 清理旧版单文件 conf（listen 80，已被 member0.conf 取代）
ssh "$SSH_TARGET" "rm -f /opt/panjia-preview/conf/default.conf"

# ---- 同步 dist（全员共享，增量）----
echo ""
echo "[3/4] 同步共享构建产物（rsync 增量）..."
rsync -az --delete --stats "$DIST_DIR/" "$SSH_TARGET:/opt/panjia-preview/dist/" | grep -E 'Number of files transferred|speedup' || true

# ---- 容器（单容器服务全员；挂载/端口方式不符时自动重建）----
echo ""
echo "[4/4] 确认 preview 容器..."
ssh "$SSH_TARGET" "
    CONF_OK=0; PORT_OK=0
    if sudo docker ps -a --format '{{.Names}}' | grep -qx panjia-preview; then
        sudo docker inspect panjia-preview --format '{{range .Mounts}}{{.Source}}:{{.Destination}} {{end}}' \
            | grep -q '/opt/panjia-preview/conf:/etc/nginx/conf.d ' && CONF_OK=1
        sudo docker inspect panjia-preview --format '{{json .HostConfig.PortBindings}}' \
            | grep -q '3100-3199/tcp' && PORT_OK=1
    fi
    if [ \"\$CONF_OK\" = 1 ] && [ \"\$PORT_OK\" = 1 ]; then
        if [ -z \"\$(sudo docker ps -q --filter name=panjia-preview)\" ]; then
            sudo docker start panjia-preview
            echo '[INFO] 容器已停止，重新启动'
        else
            echo '[SKIP] 容器运行中'
        fi
    else
        sudo docker rm -f panjia-preview 2>/dev/null || true
        sudo docker run -d --name panjia-preview \
            --restart unless-stopped \
            -p 3100-3199:3100-3199 \
            --add-host=host.docker.internal:host-gateway \
            -v /opt/panjia-preview/conf:/etc/nginx/conf.d:ro \
            -v /opt/panjia-preview/dist:/usr/share/nginx/html:ro \
            nginx:stable-alpine
        echo '[INFO] 容器已重建（目录挂载 conf + 3100-3199 整段映射）'
    fi
    sudo docker exec panjia-preview nginx -s reload 2>/dev/null && echo '[INFO] nginx 配置已 reload'
"

# ---- 验证 ----
sleep 1
HTTP_CODE=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "http://$SERVER_ADDR:$WEB_PORT/" || true)
API_CODE=$(curl -s -o /dev/null -w '%{http_code}' --max-time 15 "http://$SERVER_ADDR:$WEB_PORT/prod-api/auth/tenant/list" || true)

echo ""
echo "=========================================="
echo "  ✓ 部署完成（成员 $MEMBER）"
echo "  客户访问地址：http://$SERVER_ADDR:$WEB_PORT"
echo "  静态页面：HTTP $HTTP_CODE"
echo "  API 链路(/prod-api)：HTTP $API_CODE"
echo ""
echo "  以后前端改动后（全员同步更新）："
echo "    pnpm build && sh bin/frp/deploy-preview.sh"
echo "  新成员接入（第 N 号）："
echo "    sh bin/frp/pack-frpc.sh <名字> <3000+N> <8080+N>"
echo "    sh bin/frp/deploy-preview.sh <N>"
echo "=========================================="
