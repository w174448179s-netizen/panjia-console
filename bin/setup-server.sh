#!/bin/bash
# ============================================================================
# 全新服务器一键部署脚本（生产部署）
# 从本地 Mac 执行，自动完成：构建 → 传 → 装 Docker → 申请证书 → 启动
#
# 两种模式：
#   full    完整 HTTPS 模式：必须有可解析的域名，自动申请 Let's Encrypt 证书
#   ip-only IP 模式：域名未到，用服务器公网 IP 直接测试网站，HTTP only
#                    域名下来后跑 bin/add-domain.sh <域名> 切换到 HTTPS
#
# 用法：
#   sh bin/setup-server.sh <服务器IP> <SSH用户> <域名|-> <JKS密码> [邮箱] [镜像标签]
#
# 示例（完整 HTTPS 模式）：
#   sh bin/setup-server.sh 118.24.77.11 ubuntu www.panjia.icu MyPass123
#   sh bin/setup-server.sh 118.24.77.11 ubuntu www.panjia.icu MyPass123 admin@panjia.icu v1
#
# 示例（IP 模式，域名未到）：
#   sh bin/setup-server.sh 118.24.77.11 ubuntu - MyPass123
#   # 之后：sh bin/add-domain.sh 118.24.77.11 ubuntu www.panjia.icu
#
# 前置条件：
#   full 模式：
#     1. 域名 A 记录已指向服务器 IP（脚本会主动 dig 校验）
#     2. 腾讯云安全组已开放 80、443、22 端口
#     3. 已执行 ssh-copy-id <用户>@<服务器IP>
#     4. 非 root 用户需要 sudo NOPASSWD（脚本会主动 sudo -n 校验，失败直接退出）
#   ip-only 模式：
#     1. 腾讯云安全组已开放 80、22 端口（443 暂不需要）
#     2. 已执行 ssh-copy-id <用户>@<服务器IP>
#     3. 非 root 用户需要 sudo NOPASSWD
#
# 执行完后浏览器打开对应地址即可访问
# ============================================================================

set -e

# ==================== 参数解析 ====================
# 位置参数：
#   $1 服务器 IP，$2 SSH 用户，$3 域名（- / 空 = ip-only），
#   $4 JKS 密码（必填），$5 证书邮箱（full 模式默认 admin@<domain>），$6 镜像标签
# 标记参数：
#   --regen-jks       强制重新生成 JKS 密钥（默认保留现有密钥）
#   --force-reset-env 强制重新生成 .env（默认保留现有 .env）
#   --no-build-cache  忽略本地构建缓存（强制重新 docker save / tar czf / sed）
#
# 示例：
#   sh bin/setup-server.sh 1.2.3.4 ubuntu www.panjia.icu MyPass123
#   sh bin/setup-server.sh 1.2.3.4 ubuntu www.panjia.icu MyPass123 --regen-jks
#   sh bin/setup-server.sh 1.2.3.4 ubuntu - MyPass123 --no-build-cache
#
# 幂等性：本脚本支持重复执行，已完成的步骤会跳过并在日志中标记 [SKIP]。
# .env 保留现有值（DB 密码不变）、JKS 默认保留现有密钥，强制重置用 --regen-jks / --force-reset-env。
REGEN_JKS=0
FORCE_RESET_ENV=0
NO_BUILD_CACHE=0
ARGS=()
for arg in "$@"; do
    case "$arg" in
        --regen-jks)        REGEN_JKS=1 ;;
        --force-reset-env)  FORCE_RESET_ENV=1 ;;
        --no-build-cache)   NO_BUILD_CACHE=1 ;;
        --help|-h)
            sed -n '2,34p' "$0"
            exit 0
            ;;
        *) ARGS+=("$arg") ;;
    esac
done
set -- "${ARGS[@]}"

SERVER_ADDR="${1:?用法：sh bin/setup-server.sh <服务器IP> <SSH用户> <域名|-> <JKS密码> [邮箱] [镜像标签]}"
SSH_USER="${2:?请提供 SSH 用户名（如 ubuntu）}"

# 域名可空：填 `-` 或留空 = IP 模式（域名未到，先用 IP 测试）
if [ -z "${3:-}" ] || [ "${3:-}" = "-" ]; then
    DOMAIN=""
    MODE="ip-only"
else
    DOMAIN="${3}"
    MODE="full"
fi

JKS_PASSWORD="${4:?请提供 JKS 密码（强制必填，不允许默认值，防止仓库泄露的固定密码被复用）}"
CERT_EMAIL="${5:-}"
# full 模式默认邮箱 = admin@<domain>；ip-only 模式邮箱无意义，留空
if [ "$MODE" = "full" ] && [ -z "$CERT_EMAIL" ]; then
    CERT_EMAIL="admin@${DOMAIN#www.}"
fi
IMAGE_TAG="${6:-v1}"

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
INSTALL_DIR="/opt/panjia-console"
BACKEND_IMAGE="panjia-console:${IMAGE_TAG}"
BACKEND_TAR="panjia-console-${IMAGE_TAG}.tar"
FRONTEND_TAR="panjia-console-web-${IMAGE_TAG}.tar.gz"

# 缓存目录：用于跨次运行的产物缓存（镜像 tar / 前端 tar.gz / nginx.conf / compose 二进制）
# 放在项目内 .workbuddy/cache 是有意的——保留构建产物使得二次跑脚本只重启 Docker 不重做 600MB 镜像打包。
# 注意：本目录会包含 JKS 等敏感文件副本，gitignore 必须包含 .workbuddy/cache/
CACHE_DIR="$PROJECT_DIR/.workbuddy/cache"
CACHE_JKS="$CACHE_DIR/panjia-license.jks"
CACHE_BACKEND_TAR="$CACHE_DIR/$BACKEND_TAR"
CACHE_FRONTEND_TAR="$CACHE_DIR/$FRONTEND_TAR"
CACHE_NGINX_CONF="$CACHE_DIR/nginx-${MODE}${DOMAIN:+-$DOMAIN}.conf"
CACHE_COMPOSE_BIN="$CACHE_DIR/docker-compose"
mkdir -p "$CACHE_DIR"
chmod 700 "$CACHE_DIR"

# 临时目录：固定路径落在 cache 下，与其他产物一起不进 git
# 命名 staging 是为了和 cache 里的"长期产物"区分——这里每次跑可能变
STAGING_DIR="$CACHE_DIR/staging"
mkdir -p "$STAGING_DIR"
chmod 700 "$STAGING_DIR"

# 服务器侧 staging：固定绝对路径（SSH 用户的 ~/.cache/panjia-staging/）
# 注意不能用 $HOME：非 root 登录时 heredoc 走 sudo bash -s，$HOME 会被重置成 /root，
# 而 rsync 以登录用户身份写入 /home/<user>/...，两边错位导致 mv 找不到文件
# Ubuntu 惯例：root → /root，普通用户 → /home/<user>
if [ "$SSH_USER" = "root" ]; then
    REMOTE_STAGING="/root/.cache/panjia-staging"
else
    REMOTE_STAGING="/home/$SSH_USER/.cache/panjia-staging"
fi
# rsync 远端路径直接复用同一个绝对路径，保证和 mv 读的是同一个地方
RSYNC_TARGET_DIR="$REMOTE_STAGING"

# 数据库配置（密码自动生成）
DB_USER="panjia"
DB_NAME="panjia"
DB_PASSWORD="Pg$(openssl rand -hex 8)!"

echo "=========================================="
echo "  panjia-console 生产部署"
echo "=========================================="
echo "  服务器：  $SSH_USER@$SERVER_ADDR"
echo "  部署模式：$MODE"
if [ "$MODE" = "full" ]; then
    echo "  域名：    $DOMAIN"
    echo "  证书邮箱：$CERT_EMAIL"
    echo "  访问地址：https://$DOMAIN"
else
    echo "  域名：    （暂未提供，走 IP 模式）"
    echo "  访问地址：http://$SERVER_ADDR"
    echo "  ★ 域名下来后跑：sh bin/add-domain.sh $SERVER_ADDR $SSH_USER <你的域名>"
fi
echo "  部署目录：$INSTALL_DIR"
echo "  镜像标签：$IMAGE_TAG"
echo "  JKS 密码：$JKS_PASSWORD"
echo "  数据库密码：***（部署完成后从末尾输出查看）"
echo "=========================================="

# ==================== 步骤 1：本地准备 ====================
echo ""
echo ">>> 步骤 1/7：本地准备"

# 1.1 检查 JAVA_HOME
if [ -z "$JAVA_HOME" ]; then
    if [ -d "/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home" ]; then
        export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
        echo "  自动检测 JAVA_HOME: $JAVA_HOME"
    else
        echo "  [ERROR] 未设置 JAVA_HOME"
        exit 1
    fi
fi

# 1.2 检查 SSH 连通性
echo "  检查 SSH 连接..."
if ! ssh -o ConnectTimeout=10 -o BatchMode=yes "${SSH_USER}@${SERVER_ADDR}" "echo ok" &>/dev/null; then
    echo "  [ERROR] 无法 SSH 连接到 $SSH_USER@$SERVER_ADDR"
    echo "  请先执行：ssh-copy-id $SSH_USER@$SERVER_ADDR"
    exit 1
fi
echo "  ✓ SSH 连接正常"

# 1.3 检测 sudo 权限（强制免密，避免远程 sudo 卡住等输入密码）
REMOTE_BASH="bash -s"
if [ "$SSH_USER" != "root" ]; then
    if ssh -o ConnectTimeout=10 -o BatchMode=yes "${SSH_USER}@${SERVER_ADDR}" "sudo -n true" 2>/dev/null; then
        REMOTE_BASH="sudo bash -s"
        echo "  ✓ SSH_USER=${SSH_USER}，sudo 免密可用"
    else
        echo "  [ERROR] 用户 ${SSH_USER} 无法免密 sudo"
        echo "  本脚本会在远程跑 sudo bash -s，非免密的话会无限等待密码"
        echo "  请先在服务器配置 NOPASSWD："
        echo "    ssh ${SSH_USER}@${SERVER_ADDR} 'echo \"${SSH_USER} ALL=(ALL) NOPASSWD:ALL\" | sudo tee /etc/sudoers.d/${SSH_USER}'"
        echo "  或者改用 SSH_USER=root 直接登录"
        exit 1
    fi
else
    echo "  ✓ SSH_USER=root，直接以管理员身份执行"
fi

# 1.4 DNS A 记录预校验（仅 full 模式需要；ip-only 模式没有域名可校验）
if [ "$MODE" = "full" ]; then
    echo "  校验域名 ${DOMAIN} 解析..."
    if ! command -v dig >/dev/null 2>&1; then
        echo "  [WARN] 本机没有 dig，跳过 DNS 预校验（请确保 A 记录已配置）"
    else
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
    fi
else
    echo "  [ip-only 模式] 跳过 DNS 校验（域名下来后由 add-domain.sh 完成）"
fi

# 1.5 服务器磁盘空间检查（镜像 ~1GB，加上传重 + Postgres 初空间，至少 5GB）
# 用 NR==2 直接抓 df 的数据行（更可靠，避免 tail -1 在某些 busybox/alpine 上行为不一致）
echo "  检查服务器 /tmp 空间..."
REMOTE_AVAIL_KB=$(ssh -o ConnectTimeout=10 -o BatchMode=yes "${SSH_USER}@${SERVER_ADDR}" \
    "df -P /tmp | awk 'NR==2 {print \$4}'" 2>/dev/null) || REMOTE_AVAIL_KB=""
# 严格校验：必须是非负整数，否则提示排查而非沉默当作 0 处理
if ! [[ "${REMOTE_AVAIL_KB}" =~ ^[0-9]+$ ]]; then
    echo "  [ERROR] 无法读取服务器 /tmp 可用空间（df 返回：'${REMOTE_AVAIL_KB}'）"
    echo "  可能原因："
    echo "    1. /tmp 不存在或权限拒绝（极少数精简镜像）"
    echo "    2. 服务器 df 命令异常（手动测试：ssh ${SSH_USER}@${SERVER_ADDR} 'df -P /tmp'）"
    echo "    3. 服务器 LANG 导致 df 输出非英文列（可临时：export LANG=C）"
    exit 1
fi
if [ "${REMOTE_AVAIL_KB}" -lt 5242880 ]; then
    echo "  [ERROR] 服务器 /tmp 可用空间不足 5GB（实际 ${REMOTE_AVAIL_KB} KB）"
    echo "  至少需要：1.5GB（镜像）+ 1.5GB（Postgres 初始化 + 日志缓冲）+ 安全冗余"
    exit 1
fi
# 用 -v 传值 + 单引号包程序 + </dev/null 兜底，避免 bash 引号嵌套导致 awk syntax error
# 后者会让 gawk 进入 main block 等 stdin 输入，整个 shell 阻塞在 $(...) 上
REMOTE_AVAIL_GB=$(awk -v kb="${REMOTE_AVAIL_KB}" 'BEGIN{printf "%.1f", kb/1024/1024}' </dev/null)
echo "  ✓ /tmp 可用 ${REMOTE_AVAIL_KB} KB（约 ${REMOTE_AVAIL_GB} GB）"

# 1.6 服务器 80 端口占用检查（certbot --standalone 需要空闲 80）
echo "  检查服务器 80 端口占用..."
PORT80_USE=$(ssh -o ConnectTimeout=10 -o BatchMode=yes "${SSH_USER}@${SERVER_ADDR}" \
    "ss -tlnp 2>/dev/null | grep -E ':80\b' || true")
if [ -n "$PORT80_USE" ]; then
    echo "  [ERROR] 服务器 80 端口已被占用："
    echo "$PORT80_USE" | sed 's/^/    /'
    echo "  ACME 申请证书需要 80 端口空闲，请先停止占用进程"
    exit 1
fi
echo "  ✓ 80 端口空闲"

# 1.7 生成 JKS 密钥（默认保留现有密钥，仅 --regen-jks 时强制重生成）
# gen_keypair.sh 会写两个文件：panjia-license.jks（密钥）和 panjia-license.pub（公钥）
# 已经签发的 license 文件里嵌了公钥指纹，重新生成密钥会让现有 license 全部失效
echo "  处理 JKS 密钥..."
SOURCE_JKS="$PROJECT_DIR/script/keys/panjia-license.jks"
if [ "$REGEN_JKS" = 1 ]; then
    echo "  [FORCE] --regen-jks 已指定，重新生成密钥（警告：现有 license 将失效）"
    sh "$PROJECT_DIR/bin/gen_keypair.sh" "$JKS_PASSWORD" panjia-license
elif [ -f "$SOURCE_JKS" ]; then
    echo "  [SKIP] JKS 已存在：$SOURCE_JKS（保留现有密钥，license 兼容）"
    echo "  如需重生成：脚本加 --regen-jks 参数"
else
    sh "$PROJECT_DIR/bin/gen_keypair.sh" "$JKS_PASSWORD" panjia-license
fi
# 把 JKS 同步到 cache（staging 用 cp -p 保留 mtime，后续 rsync 跳过未变更文件）
cp -p "$SOURCE_JKS" "$CACHE_JKS" 2>/dev/null || cp "$SOURCE_JKS" "$CACHE_JKS"

# ==================== 步骤 2：构建 ====================
# 幂等策略：检测 build 产物是否齐备（与步骤 3、步骤 6 的检查标准一致）。
# 齐备条件：CACHE_BACKEND_TAR + CACHE_FRONTEND_TAR + docker image + ui/dist/ 都存在。
# 强制重新构建：--no-build-cache
echo ""
echo ">>> 步骤 2/7：构建（后端镜像 + 前端静态文件）"

BUILD_NEEDED=0
if [ "$NO_BUILD_CACHE" = 1 ]; then
    BUILD_NEEDED=1
    echo "  [FORCE] --no-build-cache 已指定，强制重新构建"
elif [ ! -f "$CACHE_BACKEND_TAR" ]; then
    BUILD_NEEDED=1
    echo "  后端镜像 tar 不存在，需要构建"
elif [ ! -f "$CACHE_FRONTEND_TAR" ]; then
    BUILD_NEEDED=1
    echo "  前端 tar.gz 不存在，需要构建"
elif ! docker image inspect "$BACKEND_IMAGE" >/dev/null 2>&1; then
    BUILD_NEEDED=1
    echo "  docker image $BACKEND_IMAGE 不存在，需要构建"
elif [ ! -d "$PROJECT_DIR/ui/dist" ] || [ -z "$(ls -A "$PROJECT_DIR/ui/dist" 2>/dev/null)" ]; then
    BUILD_NEEDED=1
    echo "  ui/dist/ 不存在或为空，需要构建前端"
fi

if [ "$BUILD_NEEDED" = 0 ]; then
    echo "  [SKIP] 构建产物已就绪（$BACKEND_IMAGE + 前端 tar.gz），如需强制重建加 --no-build-cache"
    echo "    后端镜像：$BACKEND_IMAGE ($(docker image inspect --format='{{.Size}}' "$BACKEND_IMAGE" 2>/dev/null | awk '{printf "%.0fMB", $1/1024/1024}'))"
    echo "    后端 tar：$(basename "$CACHE_BACKEND_TAR") ($(du -h "$CACHE_BACKEND_TAR" | cut -f1))"
    echo "    前端 tar：$(basename "$CACHE_FRONTEND_TAR") ($(du -h "$CACHE_FRONTEND_TAR" | cut -f1))"
else
    sh "$PROJECT_DIR/bin/build.sh" "$IMAGE_TAG"
fi

# ==================== 步骤 3：打包 ====================
echo ""
echo ">>> 步骤 3/7：打包部署文件"

# 导出后端镜像（缓存到 CACHE_DIR，避免重复打包 600MB+ 镜像）
# 缓存命中条件：CACHE_BACKEND_TAR 文件存在；强制重新打包用 --no-build-cache
echo "  导出后端镜像..."
if [ "$NO_BUILD_CACHE" = 0 ] && [ -f "$CACHE_BACKEND_TAR" ]; then
    echo "  [SKIP] 缓存命中：$(basename "$CACHE_BACKEND_TAR") ($(du -h "$CACHE_BACKEND_TAR" | cut -f1))"
else
    docker save -o "$CACHE_BACKEND_TAR" "$BACKEND_IMAGE"
    echo "  ✓ 后端镜像：$(du -h "$CACHE_BACKEND_TAR" | cut -f1)"
fi
cp "$CACHE_BACKEND_TAR" "$STAGING_DIR/$BACKEND_TAR"

# 打包前端静态文件（同上缓存逻辑）
echo "  打包前端静态文件..."
if [ "$NO_BUILD_CACHE" = 0 ] && [ -f "$CACHE_FRONTEND_TAR" ]; then
    echo "  [SKIP] 缓存命中：$(basename "$CACHE_FRONTEND_TAR") ($(du -h "$CACHE_FRONTEND_TAR" | cut -f1))"
else
    tar czf "$CACHE_FRONTEND_TAR" -C "$PROJECT_DIR/ui" dist
    echo "  ✓ 前端文件：$(du -h "$CACHE_FRONTEND_TAR" | cut -f1)"
fi
cp "$CACHE_FRONTEND_TAR" "$STAGING_DIR/$FRONTEND_TAR"

# 复制部署配置（按部署模式选择 nginx 模板）
# full    模式：sed 替换 nginx.conf 里的 www.panjia.icu → 真实域名
# ip-only 模式：用 nginx-http-only.conf 模板（HTTP only，server_name _; 兜底）
echo "  生成 nginx.conf..."
if [ "$NO_BUILD_CACHE" = 0 ] && [ -f "$CACHE_NGINX_CONF" ]; then
    echo "  [SKIP] 缓存命中：$(basename "$CACHE_NGINX_CONF")"
else
    if [ "$MODE" = "full" ]; then
        # nginx.conf 里 www.panjia.icu 出现两次：default_server 引用证书、正式 server_name
        # 用 | 作分隔符避免和 URL 路径冲突；同时校验域名不含 sed 特殊字符
        if [[ "$DOMAIN" =~ [\&\|\/] ]]; then
            echo "  [ERROR] 域名包含 sed 特殊字符（&/|），无法自动替换 nginx.conf"
            echo "  请换用普通域名（仅字母数字 . -）后再跑"
            exit 1
        fi
        sed "s|www.panjia.icu|$DOMAIN|g" "$PROJECT_DIR/deploy/nginx/nginx.conf" > "$CACHE_NGINX_CONF"
    else
        cp "$PROJECT_DIR/deploy/nginx/nginx-http-only.conf" "$CACHE_NGINX_CONF"
    fi
fi
cp "$CACHE_NGINX_CONF" "$STAGING_DIR/nginx.conf"

cp "$PROJECT_DIR/deploy/server/docker-compose.yml" "$STAGING_DIR/"
cp "$PROJECT_DIR/deploy/server/start.sh" "$STAGING_DIR/"
cp "$PROJECT_DIR/deploy/server/stop.sh" "$STAGING_DIR/"
cp "$PROJECT_DIR/deploy/server/restart.sh" "$STAGING_DIR/"
cp "$PROJECT_DIR/deploy/server/logs.sh" "$STAGING_DIR/"
cp "$CACHE_JKS" "$STAGING_DIR/"

# ==================== 步骤 3.5：本地下载 docker compose 二进制 ====================
# 思路：本地（开发机）网络通常比服务器到 docker hub 通得多，所以本地下载更快更稳。
# 下载到 CACHE_DIR/docker-compose（持久缓存），验证 ELF 可执行后由步骤 4 scp 到服务器。
# 本地下载失败也无所谓，远程会 fallback 到「docker pull compose 镜像 + 包装 v2 cli-plugin」。
echo ""
echo ">>> 步骤 3.5/7：本地下载 docker compose 二进制"

COMPOSE_LOCAL="$CACHE_COMPOSE_BIN"
COMPOSE_VERSION="5.5.0"
COMPOSE_URL_PRIMARY="https://github.com/docker/compose/releases/download/v${COMPOSE_VERSION}/docker-compose-linux-x86_64"
COMPOSE_URL_FALLBACK="https://mirror.ghproxy.com/${COMPOSE_URL_PRIMARY}"

# 缓存命中检查：文件存在就跳过
# 这是「预下载」机制的入口——你可以手工下载 docker-compose 二进制放到这个路径，脚本会直接复用
# 用户的预下载文件由用户自己保证合法性，脚本不再做 ELF / 大小 / version 校验
if [ -f "$COMPOSE_LOCAL" ]; then
    # 兜底：scp/cp 经常丢权限位，自动补一个
    [ -x "$COMPOSE_LOCAL" ] || chmod +x "$COMPOSE_LOCAL" 2>/dev/null || true
    echo "  [SKIP] 缓存命中：$COMPOSE_LOCAL"
else
    if curl -fsSL -m 30 -o "$COMPOSE_LOCAL" "$COMPOSE_URL_PRIMARY" 2>/dev/null; then
        echo "  ✓ 从 GitHub release 下载成功"
    elif curl -fsSL -m 30 -o "$COMPOSE_LOCAL" "$COMPOSE_URL_FALLBACK" 2>/dev/null; then
        echo "  ✓ 从 ghproxy 镜像下载成功"
    else
        echo "  [WARN] 本地下载失败（国内 GitHub 不稳常见）"
        echo "         兜底方案：远程服务器将走「docker pull docker/compose 镜像 + cli-plugin wrapper」"
        echo "         预下载绕过：手工下到 ${COMPOSE_LOCAL} 后 chmod +x 再跑脚本"
        echo "         下载地址：${COMPOSE_URL_PRIMARY}"
        # 下载全失败才清残骸：避免下次跑还在尝试用半截文件
        rm -f "$COMPOSE_LOCAL"
    fi
    [ -x "$COMPOSE_LOCAL" ] || chmod +x "$COMPOSE_LOCAL" 2>/dev/null || true
fi
# 不论成功失败，都同步一份到 TMP_DIR（步骤 4 scp 用）
[ -f "$COMPOSE_LOCAL" ] && cp "$COMPOSE_LOCAL" "$STAGING_DIR/docker-compose"

# ==================== 步骤 4：上传文件 ====================
echo ""
echo ">>> 步骤 4/7：上传文件到服务器"

# 创建目录
if [ "$SSH_USER" != "root" ]; then
    ssh -o ConnectTimeout=10 -o BatchMode=yes "${SSH_USER}@${SERVER_ADDR}" \
        "sudo mkdir -p $INSTALL_DIR/{keys,backups,logs,data/postgres,nginx,web,acme} && sudo chown -R $SSH_USER:$SSH_USER $INSTALL_DIR"
else
    ssh -o ConnectTimeout=10 -o BatchMode=yes "${SSH_USER}@${SERVER_ADDR}" \
        "mkdir -p $INSTALL_DIR/{keys,backups,logs,data/postgres,nginx,web,acme}"
fi

# 准备 staging 目录：所有要上传的文件都用 cp -p 从 cache 拷过来（保留 mtime）
# -p 让 rsync --update 能正确比对 mtime，未变更文件跳过
STAGING="$STAGING_DIR/upload"
rm -rf "$STAGING"
mkdir -p "$STAGING"
cp -p "$PROJECT_DIR/deploy/server/docker-compose.yml" "$STAGING/"
cp -p "$CACHE_DIR/nginx-${MODE}${DOMAIN:+-$DOMAIN}.conf" "$STAGING/nginx.conf"
cp -p "$CACHE_DIR/$BACKEND_TAR" "$STAGING/"
cp -p "$CACHE_DIR/$FRONTEND_TAR" "$STAGING/"
[ -f "$CACHE_DIR/docker-compose" ] && cp -p "$CACHE_DIR/docker-compose" "$STAGING/"
# 小脚本直接 cp（每次都更新，但 < 10KB，影响可忽略）
cp "$PROJECT_DIR/deploy/server/start.sh" "$STAGING/"
cp "$PROJECT_DIR/deploy/server/stop.sh" "$STAGING/"
cp "$PROJECT_DIR/deploy/server/restart.sh" "$STAGING/"
cp "$PROJECT_DIR/deploy/server/logs.sh" "$STAGING/"
# JKS 从 cache 拷（保留 mtime），cache 由步骤 1.7 负责生成/复用
cp -p "$CACHE_JKS" "$STAGING/panjia-license.jks"

# rsync 走 SSH 传输，目标 REMOTE_STAGING 是绝对路径（登录用户身份写入 /home/<user>/...）
# 执行顺序：rsync（步骤 4）先跑并自建目录（owner=登录用户），REMOTE_INIT（步骤 5，root）后跑，
# mkdir -p 幂等不改变 owner，mv/rm 以 root 身份操作用户文件没问题
if command -v rsync >/dev/null 2>&1; then
    # 不加 --update：服务器残留文件 + mtime 比较会让 rsync 静默跳过 jks/tar 等关键文件
    # 重装系统场景本来就是全量传，148MB tar 几秒钟，重跑不心疼
    rsync -e "ssh -o BatchMode=yes -o ConnectTimeout=10" -a \
        "$STAGING/" "${SSH_USER}@${SERVER_ADDR}:${RSYNC_TARGET_DIR}/"
else
    # 极端 fallback：rsync 不可用时回到 scp（全量）
    echo "  [WARN] rsync 不可用，回退 scp 全量传输"
    scp "$STAGING"/* "${SSH_USER}@${SERVER_ADDR}:${RSYNC_TARGET_DIR}/"
fi

# 输出报告：哪些文件被 rsync 增量跳过了
if [ -f "$CACHE_DIR/docker-compose" ]; then
    echo "  ✓ 文件上传完成（含 docker compose 二进制）"
else
    echo "  ✓ 文件上传完成（docker compose 由远程拉镜像兜底）"
fi

# ==================== 步骤 5：服务器初始化（Docker + Certbot）====================
echo ""
echo ">>> 步骤 5/7：服务器初始化（Docker + Certbot）"

ssh -o ConnectTimeout=10 -o BatchMode=yes -o ServerAliveInterval=30 -o ServerAliveCountMax=3 "${SSH_USER}@${SERVER_ADDR}" "$REMOTE_BASH" << REMOTE_INIT
set -e
INSTALL_DIR="/opt/panjia-console"
DOMAIN="$DOMAIN"
CERT_EMAIL="$CERT_EMAIL"

# 先建 staging 目录，rsync 上传的就是这里（不能放 /tmp/，会被 sticky 阻住 rsync set mtime）
mkdir -p "$REMOTE_STAGING"

# 检测系统
if [ -f /etc/os-release ]; then
    . /etc/os-release
    OS=\$ID
    echo "[服务器] 系统：\$OS \$VERSION_ID"
else
    echo "[ERROR] 无法检测操作系统"
    exit 1
fi

# ---------- 安装 Docker ----------
# 用 Docker 官方一键脚本搞定 ubuntu/debian/centos 全平台，避免手工拼 keyring + sources.list
# --mirror Aliyun 让 install.sh 内部用阿里云 docker-ce 镜像，国内机房首选
# get.docker.com install.sh 内部已处理：ca-certificates/curl/gnupg 前置、keyrings、apt-get update/install
if command -v docker &> /dev/null; then
    echo "[服务器] Docker 已安装：\$(docker --version)"
else
    echo "[服务器] 安装 Docker..."
    export DEBIAN_FRONTEND=noninteractive
    curl -fsSL https://get.docker.com | sh -s -- --mirror Aliyun

    echo "[服务器] ✓ Docker 安装完成"
fi

# ---------- Docker 镜像加速（无条件执行）----------
# 不能放在上面的 else 里——用户自己装的 docker 走 if 分支，daemon.json 不会被写，
# docker pull 直连 registry-1.docker.io 国内必超时
# 注意：mirrors.aliyun.com/docker-ce 是 apt 源不是 registry mirror，不能放 registry-mirrors
mkdir -p /etc/docker
cat > /etc/docker/daemon.json << DCEOF
{
    "registry-mirrors": [
        "https://docker.m.daocloud.io",
        "https://docker.1ms.run"
    ],
    "log-driver":"json-file",
    "log-opts":{"max-size":"100m","max-file":"3"}
}
DCEOF

# ---------- 启动 docker daemon ----------
systemctl enable docker 2>/dev/null || true
# 用 restart：已安装场景下 daemon 可能已在跑，改了 daemon.json 必须重启才生效
systemctl restart docker 2>/dev/null || systemctl start docker 2>/dev/null || true
# 等 daemon ready，否则下面 docker pull 会因 daemon not running 失败
for i in 1 2 3 4 5 6 7 8 9 10; do
    if docker info &>/dev/null; then break; fi
    sleep 1
done
if ! docker info &>/dev/null; then
    echo "[ERROR] docker daemon 启动失败"
    exit 1
fi
echo "[服务器] ✓ Docker daemon 已就绪"

# ---------- 验证/修复 docker compose v2 plugin ----------
# 三路顺次尝试：
#   1) system 装的 plugin（apt 装 docker-compose-plugin 留下的）
#   2) 本地 scp 上来的二进制（步骤 3.5 + 4 在本地下载并上传的，scp 走 SSH 协议，
#      比 docker pull 走 HTTP 协议更稳，且本地网络通常比服务器到 docker hub 通）
#   3) 兜底：docker pull docker/compose 镜像 + 写 cli-plugin wrapper（走 daemon.json 镜像源）
PLUGIN_PATH="/usr/local/lib/docker/cli-plugins/docker-compose"
LOCAL_COMPOSE="$REMOTE_STAGING/docker-compose"
COMPOSE_VERSION="5.5.0"
COMPOSE_IMAGE="docker/compose:v\${COMPOSE_VERSION}"

# 路 1: 已有 system plugin
if docker compose version &>/dev/null; then
    echo "[服务器] ✓ docker compose v2 plugin (system): \$(docker compose version --short)"

# 路 2: 用本地 scp 上来的二进制
elif [ -f "\${LOCAL_COMPOSE}" ] && "\${LOCAL_COMPOSE}" version &>/dev/null; then
    echo "[服务器] ✓ 本地 scp 上来的 compose 二进制可用: \$("\${LOCAL_COMPOSE}" version --short)"
    mkdir -p "\$(dirname "\${PLUGIN_PATH}")"
    cp "\${LOCAL_COMPOSE}" "\${PLUGIN_PATH}"
    chmod +x "\${PLUGIN_PATH}"
    if docker compose version &>/dev/null; then
        echo "[服务器] ✓ docker compose v2 plugin (scp): \$(docker compose version --short)"
    else
        echo "[ERROR] 装上 cli-plugin 后仍不可用，请联系脚本维护者"
        exit 1
    fi

# 路 3: 兜底，docker pull 镜像 + wrapper
else
    echo "[服务器] system plugin 和 scp 二进制都不可用，尝试通过 compose 镜像自动修复..."

    echo "[服务器] 拉取 compose 镜像: \${COMPOSE_IMAGE}"
    if ! docker pull "\${COMPOSE_IMAGE}"; then
        echo "[ERROR] 拉取 \${COMPOSE_IMAGE} 失败"
        echo "  排查："
        echo "  1) docker info 2>&1 | grep -A 10 'Registry Mirrors'  # 确认镜像源已生效"
        echo "  2) docker pull hello-world  # 测试基本 pull 是否通"
        echo "  3) 反馈给运维：内网到 docker.m.daocloud.io 不通"
        exit 1
    fi

    echo "[服务器] 安装 cli-plugin wrapper 到 \${PLUGIN_PATH} ..."
    mkdir -p "\$(dirname "\${PLUGIN_PATH}")"
    # 内层 heredoc 用 <<'WRAPEOF'（带单引号），里面的 $PWD 等保留字面，远程执行时由 shell 展开
    cat > "\${PLUGIN_PATH}" <<'WRAPEOF'
#!/bin/sh
# docker compose v2 cli-plugin wrapper
# 通过 docker/compose 镜像执行，避免 apt / GitHub release 在国内不可达的问题
exec docker run --rm \
    -v /var/run/docker.sock:/var/run/docker.sock \
    -v "$PWD:$PWD" \
    -w "$PWD" \
    docker/compose:v2.27.1 "$@"
WRAPEOF
    chmod +x "\${PLUGIN_PATH}"

    if docker compose version &>/dev/null; then
        echo "[服务器] ✓ docker compose 通过镜像包装成功: \$(docker compose version --short)"
    else
        echo "[ERROR] cli-plugin 包装后仍不可用"
        echo "  手动验证："
        echo "    cat \${PLUGIN_PATH}"
        echo "    docker run --rm \${COMPOSE_IMAGE} version"
        exit 1
    fi
fi

# 当前用户加入 docker 组
CURRENT_USER="\${SUDO_USER:-\$(logname 2>/dev/null || echo root)}"
if [ "\$CURRENT_USER" != "root" ] && [ -n "\$CURRENT_USER" ]; then
    usermod -aG docker "\$CURRENT_USER" 2>/dev/null || true
    echo "[服务器] 已将 \$CURRENT_USER 加入 docker 组"
fi

# ---------- 安装 Certbot ----------
if command -v certbot &> /dev/null; then
    echo "[服务器] Certbot 已安装"
else
    echo "[服务器] 安装 Certbot..."
    apt-get install -y -qq certbot
    echo "[服务器] ✓ Certbot 安装完成"
fi

# ---------- 申请 Let's Encrypt 证书（仅 full 模式）----------
if [ "$MODE" = "full" ]; then
    CERT_PATH="/etc/letsencrypt/live/\$DOMAIN/fullchain.pem"
    if [ -f "\$CERT_PATH" ]; then
        echo "[服务器] 证书已存在，跳过申请"
    else
        echo "[服务器] 申请 Let's Encrypt 证书：\$DOMAIN"
        echo "  域名：\$DOMAIN"
        echo "  邮箱：\$CERT_EMAIL"
        echo "  （需要端口 80 空闲，确保没有其他服务占用）"

        # 先确保端口 80 空闲
        if command -v docker &> /dev/null; then
            docker stop panjia-console-web 2>/dev/null || true
        fi

        certbot certonly --standalone \
            -d "\$DOMAIN" \
            --non-interactive \
            --agree-tos \
            --email "\$CERT_EMAIL" \
            --keep-until-expiring

        if [ -f "\$CERT_PATH" ]; then
            echo "[服务器] ✓ 证书申请成功"
        else
            echo "[服务器] [ERROR] 证书申请失败"
            echo "  请检查："
            echo "  1. 域名 \$DOMAIN A 记录是否指向 \$SERVER_ADDR"
            echo "  2. 安全组是否开放 80 端口"
            exit 1
        fi
    fi

    # ---------- 设置证书自动续签 ----------
    echo "[服务器] 设置证书自动续签 cron..."
    CRON_FILE="/etc/cron.d/panjia-certbot"
    # 用单引号 heredoc 写入临时文件，避免外层 \$ 展开（\$INSTALL_DIR 是字面给远程 cron 看的）
    cat > /tmp/.panjia-cron-new <<'CRONEOF'
# 每天凌晨 3 点检查证书续签，续签后让 nginx 容器 reload（不重启进程，避免踢断活跃连接）
0 3 * * * root certbot renew --webroot -w $INSTALL_DIR/acme --quiet && docker exec panjia-console-web nginx -s reload 2>/dev/null || true
CRONEOF
    # 幂等：已存在且内容一致跳过
    if [ -f "\$CRON_FILE" ] && cmp -s "\$CRON_FILE" /tmp/.panjia-cron-new; then
        echo "[服务器] [SKIP] 续签 cron 内容一致，跳过覆盖"
    else
        cp /tmp/.panjia-cron-new "\$CRON_FILE"
        chmod 644 "\$CRON_FILE"
        echo "[服务器] ✓ 续签 cron 已设置（每天 3:00 检查）"
    fi
    rm -f /tmp/.panjia-cron-new
else
    echo "[服务器] [ip-only 模式] 跳过证书申请和续签 cron"
    echo "[服务器] 创建空 /etc/letsencrypt 目录占位（docker compose 挂载点，add-domain.sh 会写入真实证书）"
    mkdir -p /etc/letsencrypt
fi

# ---------- 移动 JKS + 权限 ----------
mv "$REMOTE_STAGING/panjia-license.jks" "$INSTALL_DIR/keys/"
chmod 700 "$INSTALL_DIR/keys"
chmod 700 "$INSTALL_DIR/backups"
chmod 600 "$INSTALL_DIR/keys/panjia-license.jks"

# ---------- 防火墙 ----------
if command -v ufw &> /dev/null; then
    if ufw status | grep -q "active"; then
        ufw allow 80/tcp comment "HTTP-ACME"
        ufw allow 443/tcp comment "HTTPS"
        ufw allow 22/tcp comment "SSH"
        echo "[服务器] ✓ UFW 已开放 80、443、22"
    else
        echo "[服务器] UFW 未启用，跳过（需在腾讯云安全组配置）"
    fi
fi

echo "[服务器] ✓ 服务器初始化完成"
REMOTE_INIT

# ==================== 步骤 6：部署 + 启动 ====================
echo ""
echo ">>> 步骤 6/7：部署文件并启动"

ssh -o ConnectTimeout=10 -o BatchMode=yes -o ServerAliveInterval=30 -o ServerAliveCountMax=3 "${SSH_USER}@${SERVER_ADDR}" "$REMOTE_BASH" << REMOTE_DEPLOY
set -e
INSTALL_DIR="/opt/panjia-console"
DB_PASSWORD='$DB_PASSWORD'
DB_USER='$DB_USER'
DB_NAME='$DB_NAME'
JKS_PASSWORD='$JKS_PASSWORD'
IMAGE_TAG='$IMAGE_TAG'
BACKEND_TAR='$BACKEND_TAR'
FRONTEND_TAR='$FRONTEND_TAR'
FORCE_RESET_ENV='$FORCE_RESET_ENV'
DOMAIN='$DOMAIN'

cd "\$INSTALL_DIR"

# 移动部署文件
echo "[服务器] 整理部署文件..."
mv "$REMOTE_STAGING/docker-compose.yml" ./
mv "$REMOTE_STAGING/start.sh" "$REMOTE_STAGING/stop.sh" "$REMOTE_STAGING/restart.sh" "$REMOTE_STAGING/logs.sh" ./
mv "$REMOTE_STAGING/nginx.conf" ./nginx/
chmod +x *.sh

# 导入后端镜像（幂等：已存在跳过，避免 tar 传输 + docker load 浪费）
echo "[服务器] 导入后端镜像..."
if docker image inspect "panjia-console:\$IMAGE_TAG" >/dev/null 2>&1; then
    echo "[服务器] [SKIP] 后端镜像已存在：panjia-console:\$IMAGE_TAG"
else
    docker load -i "$REMOTE_STAGING/\$BACKEND_TAR"
    echo "[服务器] ✓ 后端镜像已导入"
fi
rm -f "$REMOTE_STAGING/\$BACKEND_TAR"

# 解压前端静态文件（幂等：web/dist 非空跳过）
echo "[服务器] 解压前端静态文件..."
if [ -d "./web/dist" ] && [ "\$(ls -A ./web/dist 2>/dev/null)" ]; then
    DIST_COUNT="\$(ls ./web/dist 2>/dev/null | wc -l | tr -d ' ')"
    echo "[服务器] [SKIP] 前端已部署：./web/dist 已有 \$DIST_COUNT 个文件"
else
    tar xzf "$REMOTE_STAGING/\$FRONTEND_TAR" -C ./web/
    echo "[服务器] ✓ 前端已解压"
fi
rm -f "$REMOTE_STAGING/\$FRONTEND_TAR"

# 生成 .env（关键幂等点：默认保留现有 .env，避免每次跑都重置 DB 密码炸现有 postgres 容器）
# 强制重置用 --force-reset-env
echo "[服务器] 生成配置文件..."
if [ -f ".env" ] && [ "\$FORCE_RESET_ENV" != "1" ]; then
    echo "[服务器] [SKIP] .env 已存在，跳过重新生成（DB 密码保持不变，postgres 容器不会断连）"
    echo "[服务器]   如需重置密码（含灾难性副作用）：脚本加 --force-reset-env 参数"
else
    if [ "\$FORCE_RESET_ENV" = "1" ] && [ -f ".env" ]; then
        echo "[服务器] [FORCE] --force-reset-env 已指定，重置 .env（DB 密码已变更！）"
    fi
    cat > .env << EOF
POSTGRES_USER=\$DB_USER
POSTGRES_PASSWORD=\$DB_PASSWORD
POSTGRES_DB=\$DB_NAME
PANJIA_JKS_PASSWORD=\$JKS_PASSWORD
PANJIA_KEY_PASSWORD=\$JKS_PASSWORD
PANJIA_OPERATOR_NAME=admin
IMAGE_TAG=\$IMAGE_TAG
HOST_PORT=80
JAVA_OPTS=-Xms512m -Xmx1024m -XX:+UseG1GC
DOMAIN=\$DOMAIN
EOF
    chmod 600 .env
fi

# 修正文件属主
CURRENT_USER="\${SUDO_USER:-\$(logname 2>/dev/null || echo root)}"
if [ "\$CURRENT_USER" != "root" ] && [ -n "\$CURRENT_USER" ]; then
    chown -R "\$CURRENT_USER:\$CURRENT_USER" "\$INSTALL_DIR"
fi

# 拉取 nginx 镜像
echo "[服务器] 拉取 nginx:stable-alpine..."
docker pull nginx:stable-alpine

# 启动
# --wait：等到 console 容器的 healthcheck 通过再返回，避免硬编码 sleep 赌启动时间
# --wait-timeout 300：healthcheck start_period 60s + 业务冷启动，综合给 5 分钟
# --remove-orphans：清掉上次失败或手工留下的孤儿容器/网络
echo "[服务器] 启动服务（--wait 等健康检查通过，最多 5 分钟）..."
docker compose up -d --wait --wait-timeout 300 --remove-orphans

echo "[服务器] 当前容器状态："
docker compose ps

REMOTE_DEPLOY

# ==================== 步骤 7：验证 ====================
echo ""
echo ">>> 步骤 7/7：验证服务"

# 步骤 6 已用 docker compose --wait 等待 healthcheck 通过，这里只做轻量联通验证
# 所有 curl 加 -m 10 兜底超时，避免证书/防火墙异常时默认 TCP 超时挂死脚本
# 按部署模式选择健康检查目标：
#   full    模式：https://$DOMAIN（HTTPS）
#   ip-only 模式：http://$SERVER_ADDR（HTTP，因没有 HTTPS 也没证书）

if [ "$MODE" = "full" ]; then
    # 检查 HTTPS
    HTTP_CODE=$(curl -s -m 10 -o /dev/null -w "%{http_code}" "https://${DOMAIN}" 2>/dev/null || echo "000")
    if [ "$HTTP_CODE" = "200" ] || [ "$HTTP_CODE" = "301" ]; then
        echo "  ✓ 前端 HTTPS 访问正常（$HTTP_CODE）"
    else
        echo "  [WARN] HTTPS 返回 $HTTP_CODE（可能上游 console 还没完全就绪）"
        echo "  稍等访问：https://$DOMAIN"
        echo "  查看日志：ssh $SSH_USER@$SERVER_ADDR 'cd $INSTALL_DIR && ./logs.sh'"
    fi

    # 检查 HTTP 跳转
    HTTP_REDIRECT=$(curl -s -m 10 -o /dev/null -w "%{http_code}" "http://${DOMAIN}" 2>/dev/null || echo "000")
    if [ "$HTTP_REDIRECT" = "301" ]; then
        echo "  ✓ HTTP → HTTPS 跳转正常"
    fi

    # 检查后端 API（LE 证书是受信任 CA，不需要 -k）
    HEALTH_CODE=$(curl -s -m 10 -o /dev/null -w "%{http_code}" "https://${DOMAIN}/api/panjia/dashboard/stats" 2>/dev/null || echo "000")
    if [ "$HEALTH_CODE" != "000" ]; then
        echo "  ✓ 后端 API 可达（$HEALTH_CODE）"
    fi
else
    # ip-only 模式：HTTP only 检查
    HTTP_CODE=$(curl -s -m 10 -o /dev/null -w "%{http_code}" "http://${SERVER_ADDR}" 2>/dev/null || echo "000")
    if [ "$HTTP_CODE" = "200" ] || [ "$HTTP_CODE" = "301" ]; then
        echo "  ✓ 前端 HTTP 访问正常（$HTTP_CODE）"
    else
        echo "  [WARN] HTTP 返回 $HTTP_CODE（可能上游 console 还没完全就绪）"
        echo "  稍等访问：http://$SERVER_ADDR"
        echo "  查看日志：ssh $SSH_USER@$SERVER_ADDR 'cd $INSTALL_DIR && ./logs.sh'"
    fi

    HEALTH_CODE=$(curl -s -m 10 -o /dev/null -w "%{http_code}" "http://${SERVER_ADDR}/api/panjia/dashboard/stats" 2>/dev/null || echo "000")
    if [ "$HEALTH_CODE" != "000" ]; then
        echo "  ✓ 后端 API 可达（$HEALTH_CODE）"
    fi
fi

# 注：TMP_DIR 由 trap EXIT 自动清理，无须显式 rm

echo ""
echo "=========================================="
if [ "$MODE" = "full" ]; then
    echo "  ✓ 生产部署完成！"
else
    echo "  ✓ IP 模式部署完成！"
fi
echo "=========================================="
echo ""
if [ "$MODE" = "full" ]; then
    echo "  前端地址：https://$DOMAIN"
    echo "  后端 API：https://$DOMAIN/api/panjia/*"
    echo "  鉴权 API：https://$DOMAIN/api/auth/*"
else
    echo "  前端地址：http://$SERVER_ADDR"
    echo "  后端 API：http://$SERVER_ADDR/api/panjia/*"
    echo "  鉴权 API：http://$SERVER_ADDR/api/auth/*"
    echo ""
    echo "  ★ 域名下来后切到 HTTPS："
    echo "    1. 域名 A 记录指向 $SERVER_ADDR"
    echo "    2. sh bin/add-domain.sh $SERVER_ADDR $SSH_USER <你的域名>"
fi
echo ""
echo "  SSH 登录：ssh $SSH_USER@$SERVER_ADDR"
echo "  部署目录：$INSTALL_DIR"
echo "  查看日志：ssh $SSH_USER@$SERVER_ADDR 'cd $INSTALL_DIR && ./logs.sh'"
echo "  重启服务：ssh $SSH_USER@$SERVER_ADDR 'cd $INSTALL_DIR && ./restart.sh'"
echo ""
echo "  ★ 重要信息（请截图保存）："
echo "    部署模式：$MODE"
if [ "$MODE" = "full" ]; then
    echo "    域名：      $DOMAIN"
    echo "    证书路径：  /etc/letsencrypt/live/$DOMAIN/"
    echo "    续签 cron： /etc/cron.d/panjia-certbot（每天 3:00）"
fi
echo "    数据库用户：$DB_USER"
echo "    数据库名称：$DB_NAME"
echo "    数据库密码：$DB_PASSWORD"
echo "    JKS 密码：  $JKS_PASSWORD"
echo "=========================================="
echo ""
if [ "$MODE" = "full" ]; then
    echo "  ★ 腾讯云安全组请确认已开放：80（ACME）、443（HTTPS）、22（SSH）"
else
    echo "  ★ 腾讯云安全组请确认已开放：80（HTTP）、22（SSH）"
    echo "  ★ 443 在 add-domain.sh 切换前可不开；切换后再开 443"
fi
echo "=========================================="
