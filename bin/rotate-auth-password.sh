#!/bin/sh
# =============================================================
# 轮换管理端 Basic Auth 密码（一键脚本）
# 用法：
#   sh bin/rotate-auth-password.sh                  # 生成 20 位随机密码
#   sh bin/rotate-auth-password.sh 我想要的密码      # 用自定义密码
#   sh bin/rotate-auth-password.sh -p 32            # 生成 32 位随机密码
# 流程：生成明文 → 写 apr1 哈希到 deploy/nginx/.htpasswd → 推送服务器生效
# 注意：明文密码只在结束时打印一次，务必当场保存
# =============================================================
set -e
# 先解析出脚本目录再 cd（避免从 bin/ 目录内运行时相对路径失效）
_SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$_SCRIPT_DIR/.."

# 服务器地址：优先级 = 环境变量 > bin/server.env（gitignored）
. "$_SCRIPT_DIR/_server-env.sh"
SERVER_IP="${SERVER_IP:?请创建 bin/server.env（模板见 bin/server.env.example）}"
SERVER_USER="${SERVER_USER:-ubuntu}"
HTPASSWD="deploy/nginx/.htpasswd"

# ---------- 解析参数 ----------
LENGTH=20
CUSTOM_PASS=""
if [ "$1" = "-p" ] && [ -n "$2" ]; then
    LENGTH="$2"
elif [ -n "$1" ]; then
    CUSTOM_PASS="$1"
fi

# ---------- 生成明文密码 ----------
if [ -n "$CUSTOM_PASS" ]; then
    PASS="$CUSTOM_PASS"
    echo "[提示] 使用自定义密码（短密码会降低安全性，建议 ≥16 位随机字符）"
else
    PASS=$(openssl rand -base64 64 | tr -d '/+=Il0O' | head -c "$LENGTH")
fi

# ---------- 写入 htpasswd（只存哈希，不存明文）----------
HASH=$(openssl passwd -apr1 "$PASS")
printf 'admin:%s\n' "$HASH" > "$HTPASSWD"
chmod 644 "$HTPASSWD"

echo "已生成新密码文件: $HTPASSWD"
echo "正在推送到服务器并生效..."
echo "=================================================="

# ---------- 推送并生效 ----------
sh bin/push-nginx.sh "$SERVER_IP" "$SERVER_USER"

echo "=================================================="
echo "  ✓ 密码轮换完成，立即生效"
echo ""
echo "  用户名: admin"
echo "  密码:   $PASS"
echo ""
echo "  ⚠️  明文只显示这一次，请立即保存到密码管理器！"
echo "=================================================="
