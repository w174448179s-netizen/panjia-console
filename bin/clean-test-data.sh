#!/usr/bin/env bash
# ============================================================================
# 清空测试数据
# ============================================================================
# 支持三种执行模式：
#   --remote  通过 SSH 连接远程服务器，在服务器的 postgres 容器中执行（推荐用于 panjia.icu）
#   --docker  在本地 Docker 的 postgres 容器中执行
#   --local   用本地 psql 执行
#   （无参数）自动检测：有 server.env 或 SERVER_IP → --remote；有本地 postgres 容器 → --docker；否则 --local
#
# 用法：
#   sh bin/clean-test-data.sh                    # 自动检测
#   sh bin/clean-test-data.sh --remote           # 远程服务器（从 bin/server.env 读取连接信息）
#   sh bin/clean-test-data.sh --docker           # 本地 Docker 容器
#   sh bin/clean-test-data.sh --local            # 本地 psql
# ============================================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
SQL_FILE="$PROJECT_DIR/sql/clean-test-data.sql"

if [ ! -f "$SQL_FILE" ]; then
    echo "[ERROR] 找不到 SQL 脚本：$SQL_FILE"
    exit 1
fi

echo "=========================================="
echo "  ⚠️  即将清空所有测试数据"
echo "  脚本：$SQL_FILE"
echo "=========================================="
echo ""
echo "保留的表："
echo "  - flyway_schema_history（Flyway 元数据）"
echo "  - auth.t_key_version（JWT 密钥版本）"
echo ""

read -r -p "确认执行？输入 yes 继续：" CONFIRM
if [ "$CONFIRM" != "yes" ]; then
    echo "已取消。"
    exit 0
fi

MODE="${1:-auto}"

# ---- 加载服务器配置（供 --remote 使用）----
. "$SCRIPT_DIR/_server-env.sh"

run_remote() {
    # 解析服务器地址（兼容旧写法：sh xxx.sh <IP> <user>）
    SERVER_ADDR=""
    SSH_USER=""
    case "${2:-}" in
        [0-9]*.[0-9]*.[0-9]*.[0-9]*)
            SERVER_ADDR="$2"
            if [ -n "${3:-}" ] && [ "${3#-}" = "$3" ]; then SSH_USER="$3"; fi
            ;;
    esac
    SERVER_ADDR="${SERVER_ADDR:-${SERVER_IP:-}}"
    SSH_USER="${SSH_USER:-${SERVER_USER:-ubuntu}}"

    if [ -z "$SERVER_ADDR" ]; then
        echo "[ERROR] 未配置服务器地址。请创建 bin/server.env（模板见 bin/server.env.example），或传入参数："
        echo "  sh bin/clean-test-data.sh --remote <服务器IP> [SSH用户]"
        exit 1
    fi

    echo "[远程] 目标：$SSH_USER@$SERVER_ADDR"

    # 连通性检查
    if ! ssh -o ConnectTimeout=10 -o BatchMode=yes "${SSH_USER}@${SERVER_ADDR}" "echo ok" &>/dev/null; then
        echo "[ERROR] 无法 SSH 连接到 $SSH_USER@$SERVER_ADDR"
        echo "  请确认：1. 服务器 IP 正确  2. SSH 免密登录已配置（ssh-copy-id）"
        exit 1
    fi

    # 查找服务器上的 postgres 容器
    local CONTAINER
    CONTAINER="$(ssh -o BatchMode=yes "${SSH_USER}@${SERVER_ADDR}" \
        "docker ps --format '{{.Names}}' 2>/dev/null | grep -i postgres | head -1")"

    if [ -z "$CONTAINER" ]; then
        echo "[ERROR] 服务器上未找到 postgres 容器"
        exit 1
    fi

    # 从容器环境变量读取用户名和数据库名
    local PG_USER PG_DB
    PG_USER="$(ssh -o BatchMode=yes "${SSH_USER}@${SERVER_ADDR}" \
        "docker exec '$CONTAINER' printenv POSTGRES_USER 2>/dev/null || echo postgres")"
    PG_DB="$(ssh -o BatchMode=yes "${SSH_USER}@${SERVER_ADDR}" \
        "docker exec '$CONTAINER' printenv POSTGRES_DB 2>/dev/null || echo postgres")"

    echo "[远程] 容器：$CONTAINER，用户：$PG_USER，数据库：$PG_DB"
    echo "[远程] 执行 psql..."

    # 通过 SSH 管道将 SQL 传给远程容器的 psql
    ssh -o BatchMode=yes "${SSH_USER}@${SERVER_ADDR}" \
        "docker exec -i '$CONTAINER' psql -U '$PG_USER' -d '$PG_DB'" < "$SQL_FILE"
}

run_docker() {
    local CONTAINER
    CONTAINER="$(docker ps --format '{{.Names}}' 2>/dev/null | grep -i postgres | head -1)"
    if [ -z "$CONTAINER" ]; then
        echo "[ERROR] 本地未找到 postgres 容器，请先启动数据库"
        exit 1
    fi
    local PG_USER PG_DB
    PG_USER="$(docker exec "$CONTAINER" printenv POSTGRES_USER 2>/dev/null || echo postgres)"
    PG_DB="$(docker exec "$CONTAINER" printenv POSTGRES_DB 2>/dev/null || echo postgres)"
    echo "[本地Docker] 容器：$CONTAINER，用户：$PG_USER，数据库：$PG_DB"
    echo "[本地Docker] 执行 psql..."
    docker exec -i "$CONTAINER" psql -U "$PG_USER" -d "$PG_DB" < "$SQL_FILE"
}

run_local() {
    if ! command -v psql >/dev/null 2>&1; then
        echo "[ERROR] 本地未安装 psql 命令。"
        echo ""
        echo "解决方案："
        echo "  macOS:  brew install libpq"
        echo "  Ubuntu: sudo apt install postgresql-client"
        echo ""
        echo "或使用远程/Docker 模式："
        echo "  sh bin/clean-test-data.sh --remote"
        echo "  sh bin/clean-test-data.sh --docker"
        exit 1
    fi
    echo "[本地] 执行 psql..."
    psql -U postgres -d postgres -f "$SQL_FILE"
}

case "$MODE" in
    --remote)
        run_remote
        ;;
    --docker)
        run_docker
        ;;
    --local)
        run_local
        ;;
    *)
        # 自动检测
        if [ -n "${SERVER_IP:-}" ] || [ -f "$SCRIPT_DIR/server.env" ]; then
            run_remote
        elif docker ps --format '{{.Names}}' 2>/dev/null | grep -qi postgres; then
            run_docker
        else
            run_local
        fi
        ;;
esac

echo ""
echo "✓ 测试数据已清空"
echo "  - 所有业务表数据已删除"
echo "  - 自增序列已重置"
echo "  - 密钥版本表保留，JWT 验证不受影响"
