#!/bin/bash
# ============================================================================
# 离线生成 RSA 密钥对（首次部署执行一次）
# ============================================================================
# 用法：./bin/gen_keypair.sh [密码] [别名]
#   密码：JKS 密钥库密码（建议至少 16 位随机字符）
#   别名：密钥别名，默认 panjia-license
#
# 输出：
#   script/keys/panjia-license.jks    —— 私钥库（权限 600，仅服务端使用）
#   script/keys/public-key.pem        —— 公钥（内嵌客户端 JAR）
#
# ★ 安全注意（H5）：
#   - jks 文件权限必须设置为 600
#   - 密码不得写入 Git、配置文件、日志
#   - jks 需离线加密备份，与数据库备份物理隔离
#   - 私钥永远不出服务器进程
# ============================================================================

set -e

KEY_PASSWORD=${1:-}
KEY_ALIAS=${2:-panjia-license}
OUTPUT_DIR="$(dirname "$0")/../script/keys"
JKS_FILE="$OUTPUT_DIR/panjia-license.jks"
PUB_KEY_FILE="$OUTPUT_DIR/public-key.pem"

if [ -z "$KEY_PASSWORD" ]; then
    echo "错误：请提供密钥密码作为第一个参数"
    echo "用法：$0 <密码> [别名]"
    exit 1
fi

mkdir -p "$OUTPUT_DIR"

# 如果已存在，先备份
if [ -f "$JKS_FILE" ]; then
    echo "检测到已有 JKS 文件，备份为 $JKS_FILE.bak"
    cp "$JKS_FILE" "$JKS_FILE.bak"
fi

echo "正在生成 RSA 2048 位密钥对..."
echo "别名：$KEY_ALIAS"
echo "输出：$JKS_FILE"

# 生成 JKS 密钥库
keytool -genkeypair \
    -alias "$KEY_ALIAS" \
    -keyalg RSA \
    -keysize 2048 \
    -sigalg SHA256withRSA \
    -keystore "$JKS_FILE" \
    -storepass "$KEY_PASSWORD" \
    -keypass "$KEY_PASSWORD" \
    -dname "CN=panjia-license, OU=panjia, O=panjia, L=Shanghai, ST=Shanghai, C=CN" \
    -validity 3650 \
    -storetype JKS

# 导出公钥（PEM 格式，用于内嵌客户端）
echo "正在导出公钥到 $PUB_KEY_FILE ..."
keytool -exportcert \
    -alias "$KEY_ALIAS" \
    -keystore "$JKS_FILE" \
    -storepass "$KEY_PASSWORD" \
    -rfc \
    -file "$PUB_KEY_FILE"

# ★ 设置 JKS 权限为 600（仅属主可读）
chmod 600 "$JKS_FILE"
echo "已设置 JKS 文件权限为 600"

echo ""
echo "============================================"
echo "  密钥对生成完成"
echo "============================================"
echo "  私钥库：$JKS_FILE"
echo "  公钥：  $PUB_KEY_FILE"
echo ""
echo "  ★ 请妥善保管 JKS 密码：$KEY_PASSWORD"
echo "  ★ JKS 文件需离线加密备份，与数据备份物理隔离"
echo "  ★ 公钥需内嵌到客户端 JAR 中"
echo "============================================"

# 显示公钥指纹（便于核对）
echo ""
echo "公钥指纹："
keytool -list -alias "$KEY_ALIAS" -keystore "$JKS_FILE" -storepass "$KEY_PASSWORD" -v | grep -E "指纹|SHA256"
