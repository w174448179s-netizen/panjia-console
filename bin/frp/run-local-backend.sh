#!/bin/bash
# ============================================================================
# 本地后端 Docker 化 —— 与 IDE 开发 8080 互不干扰
#
# 场景：
#   你平时用 IDE（8080）开发调试，但需要让远程 preview 持续访问一个稳定版本。
#   本脚本把后端打包进 Docker，暴露到宿主机 18080，远程走 frp → 18080；
#   IDE 开发继续用 8080，两者完全独立。
#
# 前置条件：
#   1. 本机 Docker 可用
#   2. 中间件（postgres / redis / minio）已通过 script/docker/docker-compose.yml 启动
#   3. Maven 已安装（用于打包）
#
# 用法：
#   sh bin/frp/run-local-backend.sh              # 构建并启动
#   sh bin/frp/run-local-backend.sh --skip-build # 不重新打包，直接用现有 jar
#   sh bin/frp/run-local-backend.sh --stop       # 只停止容器
#
# 修改点（一次性）：
#   改 frpc.toml 中 localPort = 18080（原为 8080），然后 docker restart panjia-frpc
# ============================================================================

set -e

SKIP_BUILD=0
STOP_ONLY=0
for arg in "$@"; do
    case "$arg" in
        --skip-build) SKIP_BUILD=1 ;;
        --stop) STOP_ONLY=1 ;;
        --help|-h)
            awk 'NR==1{next} /^#/{sub(/^# ?/,""); print; next} {exit}' "$0"
            exit 0 ;;
    esac
done

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SERVER_DIR="$(cd "$SCRIPT_DIR/../../../panjia-server" && pwd)"

cd "$SERVER_DIR"

# ---- 停止模式 ----
if [ "$STOP_ONLY" = 1 ]; then
    echo "[STOP] 停止 panjia-local-backend 容器..."
    docker rm -f panjia-local-backend 2>/dev/null && echo "已停止" || echo "容器不存在"
    exit 0
fi

# ---- 检查 jar ----
JAR="$SERVER_DIR/ruoyi-admin/target/ruoyi-admin.jar"
if [ "$SKIP_BUILD" = 0 ]; then
    echo "=========================================="
    echo "  本地后端 Docker 化"
    echo "  项目：$SERVER_DIR"
    echo "=========================================="
    echo ""
    echo "[1/3] Maven 打包（ruoyi-admin + 依赖模块）..."
    mvn clean package -pl ruoyi-admin -am -DskipTests -q
else
    if [ ! -f "$JAR" ]; then
        echo "[ERROR] 未找到 $JAR，请先执行 mvn package 或去掉 --skip-build"
        exit 1
    fi
    echo "=========================================="
    echo "  本地后端 Docker 化（跳过构建）"
    echo "=========================================="
fi

# ---- 构建镜像 ----
echo ""
echo "[2/3] Docker 构建镜像 panjia-local-backend:latest ..."
docker build -f Dockerfile.local -t panjia-local-backend:latest .

# ---- 启动容器 ----
echo ""
echo "[3/3] 启动容器（宿主机 18080 → 容器 8080）..."
docker rm -f panjia-local-backend 2>/dev/null || true

docker run -d --name panjia-local-backend \
    -p 18080:8080 \
    --add-host=host.docker.internal:host-gateway \
    panjia-local-backend:latest

# ---- 健康检查 ----
echo ""
echo "等待服务启动（约 10~30 秒）..."
for i in $(seq 1 30); do
    CODE=$(curl -s -o /dev/null -w '%{http_code}' \
        --max-time 2 http://localhost:18080/prod-api/auth/tenant/list 2>/dev/null || true)
    if [ "$CODE" = "200" ]; then
        echo "✓ 健康检查通过（HTTP 200）"
        break
    fi
    sleep 2
    if [ "$i" = "30" ]; then
        echo "⚠ 服务启动较慢，请稍后手动检查：curl http://localhost:18080/prod-api/auth/tenant/list"
    fi
done

echo ""
echo "=========================================="
echo "  ✓ 后端已运行在 Docker"
echo "  宿主机 API：http://localhost:18080"
echo ""
echo "  【下一步：改 frpc.toml】"
echo "    打开 panjia-console/bin/frp/frpc.toml"
echo "    把 localPort = 8080 改为 localPort = 18080"
echo "    然后执行：docker restart panjia-frpc"
echo ""
echo "  本地 IDE 开发继续用 8080，不受影响。"
echo "  停止容器：sh bin/frp/run-local-backend.sh --stop"
echo "=========================================="
