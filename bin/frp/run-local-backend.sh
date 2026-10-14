#!/bin/bash
# ============================================================================
# 0 号开发机后端 Docker 化 —— 整栈同构（与客户机部署形态一致）
#
# 场景：
#   0 号开发机与客户机同构：web nginx(80) 服务前端 + /prod-api + /oss，
#   frp 单隧道 panjia.icu:3000 → 本机 80。本脚本把后端打包进 Docker，
#   接入 docker_default 网络（别名 server），使 deploy/docker/nginx.conf 原样工作；
#   IDE 开发（8080）与 Docker 后端（18080 直连调试）互不干扰。
#
# 前置条件：
#   1. 本机 Docker 可用
#   2. 中间件（postgres / redis / minio / web nginx）已通过 script/docker/docker-compose.yml 启动
#   3. Maven 已安装（用于打包）
#
# 用法：
#   sh bin/frp/run-local-backend.sh              # 构建并启动
#   sh bin/frp/run-local-backend.sh --skip-build # 不重新打包，直接用现有 jar
#   sh bin/frp/run-local-backend.sh --stop       # 只停止容器
#
# License 激活（prod 构建，与客户机一致）：
#   首次启动或 token 丢失后：PANJIA_AUTH_CODE=PJ-XXXX-XXXX-XXXX sh bin/frp/run-local-backend.sh
#   激活成功后 token 持久化到 panjia-local-license 卷（容器内 /data/panjia-license），
#   之后重启/重建容器自动加载，授权码一次性作废，无需再传。
#   发码：panjia-console 管理后台签发（POST /api/v1/license-mgmt/issue，licenseType=TEST）。
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
# DOCKER_BUILDKIT=1：启用 BuildKit，分层缓存命中时明确显示 CACHED，避免重复安装 postgresql-client
DOCKER_BUILDKIT=1 docker build -f Dockerfile.local -t panjia-local-backend:latest .

# ---- 启动容器 ----
echo ""
echo "[3/3] 启动容器（接入 docker_default 网络，别名 server → 容器 8080）..."
docker rm -f panjia-local-backend 2>/dev/null || true

# --network docker_default：与本地 minio/postgres/redis 同网（nginx /oss 直达 minio）
# --network-alias server：deploy/docker/nginx.conf 的 /prod-api 上游是 http://server:8080，
#                         别名让同一份 nginx 配置在本地与客户机上原样工作
# -v panjia-local-license:/data：License 状态（.panjia_token/.panjia_instance_id/.panjia_monotonic）
#                         持久化到命名卷——容器重建不丢 token，指纹稳定，不会反复触发激活
# -e PANJIA_AUTH_CODE：一次性激活码，首次/换机时传入；为空且卷内已有 token 则直接加载
docker run -d --name panjia-local-backend \
    --network docker_default \
    --network-alias server \
    -p 18080:8080 \
    -v panjia-local-license:/data \
    -e PANJIA_AUTH_CODE="${PANJIA_AUTH_CODE:-}" \
    --add-host=host.docker.internal:host-gateway \
    panjia-local-backend:latest

# ---- 健康检查 ----
echo ""
echo "等待服务启动（约 10~30 秒）..."
for i in $(seq 1 30); do
    # 直连后端不带 /prod-api 前缀（该前缀由 preview nginx 反代时剥离）
    CODE=$(curl -s -o /dev/null -w '%{http_code}' \
        --max-time 2 http://localhost:18080/auth/tenant/list 2>/dev/null || true)
    if [ "$CODE" = "200" ]; then
        echo "✓ 健康检查通过（HTTP 200）"
        break
    fi
    sleep 2
    if [ "$i" = "30" ]; then
        echo "⚠ 服务启动较慢或启动失败，请检查：docker logs --tail 50 panjia-local-backend"
        echo "  若日志含「未检测到有效 token / 拒绝启动」，需携带新授权码重新执行："
        echo "  PANJIA_AUTH_CODE=PJ-XXXX-XXXX-XXXX sh bin/frp/run-local-backend.sh"
        echo "  （发码：panjia-console 管理后台 → 授权签发，licenseType=TEST）"
    fi
done

echo ""
echo "=========================================="
echo "  ✓ 后端已运行在 Docker"
echo "  宿主机 API：http://localhost:18080"
echo "  同源入口（与客户机一致）：http://localhost/（本地 web nginx）"
echo "  外网入口：http://panjia.icu:3000（frp 单隧道 3000 → 本机 80）"
echo ""
echo "  前端 dist 更新后执行：cd panjia-ui && pnpm build（web nginx 即时生效，无需重启）"
echo "  本地 IDE 开发：cd panjia-ui && pnpm dev（vite 在 5173 端口，与 80 不冲突）"
echo "  停止容器：sh bin/frp/run-local-backend.sh --stop"
echo "=========================================="
