#!/bin/bash
# ============================================================================
# 二阶段：把 IP 模式部署的服务器切到 HTTPS 域名
#
# 适用场景：
#   - 已经跑过 bin/setup-server.sh 走 IP 模式部署（域名还没下来前先用 IP 测通了）
#   - 现在域名审核通过、ICP 备案好了、A 记录已经指向服务器 IP
#
# 它会做什么：
#   1. 校验 DNS A 记录
#   2. 在服务器上临时停 nginx 容器（释放 80 端口给 certbot）
#   3. 用 certbot --standalone 申请 Let's Encrypt 证书
#   4. 重新生成 HTTPS 版 nginx.conf（sed 替换 www.panjia.icu → 真实域名）
#   5. 启动 nginx 容器并 reload（不重启容器）
#   6. 设置证书自动续签 cron
#   7. 更新 .env 的 DOMAIN 字段（让后端能感知）
#   8. HTTPS 健康检查
#
# 它不会做什么（数据保留）：
#   - 不重建 docker 容器，不重启 postgres/console
#   - 不动数据库 / JKS / 现有用户数据
#   - 不改业务镜像
#
# 用法：
#   sh bin/add-domain.sh                             # 全部读 bin/server.env（推荐，零参数）
#   sh bin/add-domain.sh <域名> [邮箱]               # 只覆盖域名
#   sh bin/add-domain.sh <服务器IP> <SSH用户> <域名> [邮箱]   # 旧写法，兼容
#
# 示例：
#   sh bin/add-domain.sh www.panjia.icu
#   sh bin/add-domain.sh www.panjia.icu admin@panjia.icu
#
# 前置条件：
#   1. setup-server.sh 已经以 IP 模式成功跑过
#   2. 域名 A 记录已指向服务器 IP（脚本会主动 dig 校验）
#   3. 腾讯云安全组已开放 80、443 端口（443 在跑本脚本前需要开放）
#   4. 已执行 ssh-copy-id <用户>@<服务器IP>
#   5. 非 root 用户需要 sudo NOPASSWD
# ============================================================================

set -e

# ==================== 参数：地址/用户可省略，省略时读 bin/server.env ====================
. "$(dirname "$0")/_server-env.sh"
SERVER_ADDR=""; SSH_USER=""
case "${1:-}" in
    [0-9]*.[0-9]*.[0-9]*.[0-9]*)
        SERVER_ADDR="$1"; shift
        case "${1:-}" in
            ""|-*|*.*) ;;   # 空/flag/含点(多半是域名)→不当作用户名
            *) SSH_USER="$1"; shift ;;
        esac ;;
esac
SERVER_ADDR="${SERVER_ADDR:-${SERVER_IP:?请创建 bin/server.env（模板见 bin/server.env.example），或传入参数 <服务器IP>}}"
SSH_USER="${SSH_USER:-${SERVER_USER:-ubuntu}}"
DOMAIN="${1:-${DOMAIN:?请提供域名（如 www.panjia.icu），或在 bin/server.env 配置 DOMAIN}}"
CERT_EMAIL="${2:-${CERT_EMAIL:-admin@${DOMAIN#www.}}}"
INSTALL_DIR="${INSTALL_DIR:-/opt/panjia-console}"   # 可在 bin/server.env 配置

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"

echo "=========================================="
echo "  panjia-console 二阶段：添加域名"
echo "=========================================="
echo "  服务器：  $SSH_USER@$SERVER_ADDR"
echo "  域名：    $DOMAIN"
echo "  证书邮箱：$CERT_EMAIL"
echo "=========================================="

# ==================== 校验 ====================

# 1.1 DNS A 记录预校验
echo ""
echo ">>> 1/6：DNS 预校验"
if command -v dig >/dev/null 2>&1; then
    RESOLVED_IP="$(dig +short "$DOMAIN" 2>/dev/null | grep -E '^[0-9.]+$' | tail -1)"
    if [ -z "$RESOLVED_IP" ]; then
        echo "  [ERROR] 域名 ${DOMAIN} 解析失败"
        echo "  请在域名服务商把 A 记录指向 ${SERVER_ADDR}"
        exit 1
    elif [ "$RESOLVED_IP" != "$SERVER_ADDR" ]; then
        echo "  [ERROR] 域名 ${DOMAIN} 当前解析到 ${RESOLVED_IP}，但服务器是 ${SERVER_ADDR}"
        echo "  请修正 A 记录指向 ${SERVER_ADDR}，等 DNS 生效后再重试"
        exit 1
    fi
    echo "  ✓ DNS 解析正确：${DOMAIN} → ${RESOLVED_IP}"
else
    echo "  [WARN] 本机没有 dig，跳过 DNS 预校验（请确保 A 记录已配置）"
fi

# 1.2 SSH 连通性
echo ""
echo ">>> 2/6：检查 SSH 连接"
if ! ssh -o ConnectTimeout=10 -o BatchMode=yes "${SSH_USER}@${SERVER_ADDR}" "echo ok" &>/dev/null; then
    echo "  [ERROR] 无法 SSH 连接到 $SSH_USER@$SERVER_ADDR"
    exit 1
fi
echo "  ✓ SSH 连接正常"

# 1.3 检测 sudo 免密（与 setup-server.sh 一致）
REMOTE_BASH="bash -s"
if [ "$SSH_USER" != "root" ]; then
    if ssh -o BatchMode=yes "${SSH_USER}@${SERVER_ADDR}" "sudo -n true" 2>/dev/null; then
        REMOTE_BASH="sudo bash -s"
        echo "  ✓ sudo 免密可用"
    else
        echo "  [ERROR] 用户 ${SSH_USER} 无法免密 sudo"
        echo "  请先在服务器配置 NOPASSWD："
        echo "    ssh ${SSH_USER}@${SERVER_ADDR} 'echo \"${SSH_USER} ALL=(ALL) NOPASSWD:ALL\" | sudo tee /etc/sudoers.d/${SSH_USER}'"
        exit 1
    fi
fi

# 1.4 域名 sed 特殊字符校验
if [[ "$DOMAIN" =~ [\&\|\/] ]]; then
    echo "  [ERROR] 域名包含 sed 特殊字符（&/|），无法自动替换 nginx.conf"
    exit 1
fi

# ==================== 在服务器上操作 ====================
# 整体逻辑：
#   1) 临时停 nginx 容器，释放 80 端口
#   2) certbot --standalone 申请证书
#   3) 在服务器上生成新的 nginx.conf（HTTPS 版），覆盖现有 IP-only 版
#   4) 启动 nginx 容器（如果之前停过），reload 配置
#   5) 写续签 cron
#   6) 更新 .env 的 DOMAIN 字段

# 准备服务器上的 nginx.conf（HTTPS 版本，sed 替换后的成品）
NGINX_CONF_LOCAL="$(mktemp -t panjia-nginx-https.XXXXXX)"
sed "s|www.panjia.icu|$DOMAIN|g" "$PROJECT_DIR/deploy/nginx/nginx.conf" > "$NGINX_CONF_LOCAL"
trap 'rm -f "$NGINX_CONF_LOCAL"' EXIT INT TERM

echo ""
echo ">>> 3/6：上传新的 nginx.conf 到服务器"
scp "$NGINX_CONF_LOCAL" "${SSH_USER}@${SERVER_ADDR}:/tmp/nginx.conf.new"

echo ""
echo ">>> 4/6：申请证书 + 切换 nginx 配置 + reload"

# 让本脚本在远程服务器跑多步操作
ssh "${SSH_USER}@${SERVER_ADDR}" "$REMOTE_BASH" << REMOTE_ADD_DOMAIN
set -e
INSTALL_DIR="$INSTALL_DIR"
DOMAIN="$DOMAIN"
CERT_EMAIL="$CERT_EMAIL"

# ---------- 临时停 nginx 容器，释放 80 端口 ----------
echo "[服务器] 临时停 nginx 容器以释放 80 端口..."
if command -v docker >/dev/null 2>&1; then
    docker stop panjia-console-web 2>/dev/null || true
fi

# ---------- 申请证书 ----------
CERT_PATH="/etc/letsencrypt/live/\$DOMAIN/fullchain.pem"
if [ -f "\$CERT_PATH" ]; then
    echo "[服务器] 证书已存在，跳过申请"
else
    echo "[服务器] 申请 Let's Encrypt 证书：\$DOMAIN"
    echo "  邮箱：\$CERT_EMAIL"

    certbot certonly --standalone \
        -d "\$DOMAIN" \
        --non-interactive \
        --agree-tos \
        --email "\$CERT_EMAIL" \
        --keep-until-expiring

    if [ ! -f "\$CERT_PATH" ]; then
        echo "[ERROR] 证书申请失败"
        echo "  请检查："
        echo "  1. 域名 \$DOMAIN A 记录是否指向 \$SERVER_ADDR"
        echo "  2. 腾讯云安全组是否已开放 80、443 端口"
        echo "  3. nginx 容器是否真的停了（certbot 需要 80 空闲）"
        exit 1
    fi
    echo "[服务器] ✓ 证书申请成功"
fi

# ---------- 替换 nginx 配置 ----------
echo "[服务器] 部署新的 nginx.conf（HTTPS 版本）..."
mv /tmp/nginx.conf.new "\$INSTALL_DIR/nginx/nginx.conf"
chmod 644 "\$INSTALL_DIR/nginx/nginx.conf"

# ---------- 启动 nginx 容器并 reload ----------
echo "[服务器] 启动 nginx 容器..."
# nginx 容器配置里 depends_on console 容器。正常情况下它已经在运行，
# 只是上面为了释放 80 端口被我们 stop 了。这里用 docker compose up -d
# 把所有相关容器拉起来（如果其他容器已经 running 是 no-op），
# 然后 reload nginx 让新配置生效（不重启进程）。
cd "\$INSTALL_DIR"
docker compose up -d --remove-orphans 2>&1 | tail -5

# 等容器起来（最多 30 秒）
for i in 1 2 3 4 5 6 7 8 9 10; do
    if docker ps --format '{{.Names}}' | grep -q "^panjia-console-web\$"; then
        break
    fi
    sleep 1
done

if ! docker ps --format '{{.Names}}' | grep -q "^panjia-console-web\$"; then
    echo "[ERROR] nginx 容器未启动"
    exit 1
fi

echo "[服务器] reload nginx 配置..."
docker exec panjia-console-web nginx -t 2>&1
docker exec panjia-console-web nginx -s reload
echo "[服务器] ✓ nginx reload 完成"

# ---------- 设置证书自动续签 cron ----------
echo "[服务器] 设置证书自动续签 cron..."
cat > /etc/cron.d/panjia-certbot << CRONEOF
# 每天凌晨 3 点检查证书续签，续签后让 nginx 容器 reload（不重启进程，避免踢断活跃连接）
0 3 * * * root certbot renew --webroot -w \$INSTALL_DIR/acme --quiet && docker exec panjia-console-web nginx -s reload 2>/dev/null || true
CRONEOF
chmod 644 /etc/cron.d/panjia-certbot
echo "[服务器] ✓ 续签 cron 已设置（每天 3:00 检查）"

# ---------- 更新 .env 的 DOMAIN 字段 ----------
echo "[服务器] 更新 .env 的 DOMAIN 字段..."
if [ -f "\$INSTALL_DIR/.env" ]; then
    if grep -q "^DOMAIN=" "\$INSTALL_DIR/.env"; then
        sed -i "s|^DOMAIN=.*|DOMAIN=\$DOMAIN|" "\$INSTALL_DIR/.env"
    else
        echo "DOMAIN=\$DOMAIN" >> "\$INSTALL_DIR/.env"
    fi
    echo "[服务器] ✓ .env DOMAIN=\$DOMAIN"
fi

# ---------- 防火墙（如有 ufw）----------
if command -v ufw &> /dev/null && ufw status | grep -q "active"; then
    ufw allow 443/tcp comment "HTTPS"
    echo "[服务器] ✓ UFW 已开放 443"
fi

echo "[服务器] ✓ 域名切换完成"
REMOTE_ADD_DOMAIN

# ==================== 健康检查 ====================
echo ""
echo ">>> 5/6：HTTPS 健康检查"

HTTP_CODE=$(curl -s -m 15 -o /dev/null -w "%{http_code}" "https://${DOMAIN}" 2>/dev/null || echo "000")
if [ "$HTTP_CODE" = "200" ] || [ "$HTTP_CODE" = "301" ]; then
    echo "  ✓ HTTPS 访问正常（$HTTP_CODE）"
else
    echo "  [WARN] HTTPS 返回 $HTTP_CODE"
    echo "  可能原因："
    echo "  1. DNS 刚刚生效，TTL 缓存（dig $DOMAIN 验证）"
    echo "  2. 腾讯云安全组 443 没开"
    echo "  3. nginx reload 失败（ssh 查看：docker logs panjia-console-web）"
fi

HTTP_REDIRECT=$(curl -s -m 10 -o /dev/null -w "%{http_code}" "http://${DOMAIN}" 2>/dev/null || echo "000")
if [ "$HTTP_REDIRECT" = "301" ]; then
    echo "  ✓ HTTP → HTTPS 跳转正常"
fi

HEALTH_CODE=$(curl -s -m 10 -o /dev/null -w "%{http_code}" "https://${DOMAIN}/api/panjia/dashboard/stats" 2>/dev/null || echo "000")
if [ "$HEALTH_CODE" != "000" ]; then
    echo "  ✓ 后端 API 可达（$HEALTH_CODE）"
fi

echo ""
echo ">>> 6/6：完成"

echo ""
echo "=========================================="
echo "  ✓ 域名切换完成！"
echo "=========================================="
echo ""
echo "  前端地址：https://$DOMAIN"
echo "  后端 API：https://$DOMAIN/api/panjia/*"
echo "  鉴权 API：https://$DOMAIN/api/auth/*"
echo ""
echo "  ★ 提醒："
echo "    1. 腾讯云安全组确认已开放 443"
echo "    2. 浏览器强制刷新（Ctrl+Shift+R）清掉旧 HTTP 缓存"
echo "    3. 如之前用 IP 登录过，建议清掉浏览器 cookie 重新登录"
echo "=========================================="