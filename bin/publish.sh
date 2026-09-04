#!/bin/bash
# ============================================================================
# 一键发布：后端 + 前端 + 健康检查
#
# 适用场景：改完代码想一次推到服务器,无需手动跑多个脚本
#
# 用法：
#   sh bin/publish.sh <服务器IP> <SSH用户> [镜像标签]
#   sh bin/publish.sh <服务器IP> <SSH用户> --backend-only
#   sh bin/publish.sh <服务器IP> <SSH用户> --frontend-only
#
# 流程：
#   1) push-backend.sh  --force-recreate 重建 console 容器
#   2) push-frontend.sh 清空 web/dist 再解压新 dist
#   3) 健康检查 GET / 和 GET /api/v1/dashboard/stats
# ============================================================================

set -e

SERVER_ADDR="${1:?用法：sh bin/publish.sh <IP> <USER> [tag] [--backend-only|--frontend-only]}"
SSH_USER="${2:?请提供 SSH 用户名}"
shift 2

# 解析剩余参数：tag (位置) + --backend-only / --frontend-only (flag)
IMAGE_TAG="v1"
MODE="full"  # full | backend-only | frontend-only
for arg in "$@"; do
    case "$arg" in
        --backend-only)  MODE="backend-only" ;;
        --frontend-only) MODE="frontend-only" ;;
        -*)              echo "[ERROR] 未知参数：$arg"; exit 1 ;;
        *)               IMAGE_TAG="$arg" ;;
    esac
done

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
PUSH_BACKEND="$PROJECT_DIR/bin/push-backend.sh"
PUSH_FRONTEND="$PROJECT_DIR/bin/push-frontend.sh"

# 通过性检查: 两个 push 脚本必须在
for f in "$PUSH_BACKEND" "$PUSH_FRONTEND"; do
    [ -x "$f" ] || [ -f "$f" ] || { echo "[ERROR] 缺少 $f"; exit 1; }
done

echo "=========================================="
echo "  一键发布"
echo "  服务器：${SSH_USER}@${SERVER_ADDR}"
echo "  标签：  $IMAGE_TAG"
echo "  范围：  $MODE"
echo "=========================================="

START=$(date +%s)

# ---- 后端 ----
if [ "$MODE" != "frontend-only" ]; then
    echo ""
    echo ">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>"
    echo "  [1/3] 推送后端"
    echo ">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>"
    bash "$PUSH_BACKEND" "$SERVER_ADDR" "$SSH_USER" "$IMAGE_TAG"
fi

# ---- 前端 ----
if [ "$MODE" != "backend-only" ]; then
    echo ""
    echo ">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>"
    echo "  [2/3] 推送前端"
    echo ">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>"
    bash "$PUSH_FRONTEND" "$SERVER_ADDR" "$SSH_USER" "$IMAGE_TAG"
fi

# ---- 健康检查 ----
echo ""
echo ">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>"
echo "  [3/3] 健康检查"
echo ">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>"
FAIL=0

# 前端资源:nginx 静态文件,即时响应,一次就好
check_url_once() {
    local url="$1" label="$2"
    local code
    code=$(curl -s -m 10 -o /dev/null -w "%{http_code}" "$url" 2>/dev/null || echo "000")
    if [ "$code" = "200" ]; then
        printf "  %-40s HTTP %s ✓\n" "$label" "$code"
    else
        printf "  %-40s HTTP %s ✗\n" "$label" "$code"
        FAIL=1
    fi
}

# 后端 API:Sprint Boot + JPA + HikariCP 需要 warm up,重试兜底
# docker compose --wait 只等容器端口可达,不等应用层真正响应
check_url_retry() {
    local url="$1" label="$2" max="${3:-15}" # 默认 15 次 × 2s = 30 秒
    local code=""
    for i in $(seq 1 "$max"); do
        code=$(curl -s -m 5 -o /dev/null -w "%{http_code}" "$url" 2>/dev/null || echo "000")
        [ "$code" = "200" ] && {
            printf "  %-40s HTTP %s ✓ (第 %d/%d 次)\n" "$label" "$code" "$i" "$max"
            return 0
        }
        sleep 2
    done
    printf "  %-40s HTTP %s ✗ (重试 %d 次后仍失败)\n" "$label" "$code" "$max"
    FAIL=1
}

check_url_once "http://${SERVER_ADDR}/"           "首页 /"
check_url_once "http://${SERVER_ADDR}/favicon.ico" "favicon"
if [ "$MODE" != "frontend-only" ]; then
    check_url_retry "http://${SERVER_ADDR}/api/v1/dashboard/stats" "后端 API"
fi

# 容器状态
echo ""
echo "  --- 容器状态 ---"
ssh -o ConnectTimeout=5 -o BatchMode=yes "${SSH_USER}@${SERVER_ADDR}" \
    "cd /opt/panjia-console && sudo docker compose ps" 2>&1 | sed 's/^/  /'

ELAPSED=$(( $(date +%s) - START ))
echo ""
if [ "$FAIL" = "0" ]; then
    echo "=========================================="
    echo "  ✓ 发布完成(耗时 ${ELAPSED}s)"
    echo "=========================================="
else
    echo "=========================================="
    echo "  ✗ 发布完成但健康检查未通过(耗时 ${ELAPSED}s)"
    echo "=========================================="
    exit 1
fi
