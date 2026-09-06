#!/bin/sh
# ============================================================================
# 公共库：服务器部署配置（不要直接执行本文件，由 bin/*.sh source）
#
# 职责：
#   1. 加载 bin/server.env（已被 .gitignore 忽略，服务器信息不入 Git）
#   2. 支持环境变量覆盖：SERVER_IP=x.x.x.x INSTALL_DIR=/srv/app sh bin/xxx.sh
#      优先级：环境变量 > server.env > 脚本内兜底默认值
#
# 加载的变量：
#   SERVER_IP / SERVER_USER   SSH 连接
#   INSTALL_DIR               服务器安装目录（脚本兜底 /opt/panjia-console）
#   IMAGE_TAG                 镜像标签（脚本兜底 v1）
#   DOMAIN / CERT_EMAIL       域名与证书邮箱（add-domain.sh / setup-server.sh 用）
#
# 各脚本 source 本文件后，接如下片段即可支持「旧写法参数可选」：
#   SERVER_ADDR=""; SSH_USER=""
#   case "${1:-}" in
#       [0-9]*.[0-9]*.[0-9]*.[0-9]*)
#           SERVER_ADDR="$1"; shift
#           if [ "$#" -gt 0 ] && [ "${1#-}" = "$1" ]; then SSH_USER="$1"; shift; fi ;;
#   esac
#   SERVER_ADDR="${SERVER_ADDR:-${SERVER_IP:?请创建 bin/server.env（模板见 bin/server.env.example）}}"
#   SSH_USER="${SSH_USER:-${SERVER_USER:-ubuntu}}"
# ============================================================================

_ENV_FILE="$(cd "$(dirname "$0")" && pwd)/server.env"

if [ -f "$_ENV_FILE" ]; then
    # 先备份已设置的环境变量（环境变量优先于 server.env）
    _OVERRIDE_IP="${SERVER_IP:-}"
    _OVERRIDE_USER="${SERVER_USER:-}"
    _OVERRIDE_INSTALL_DIR="${INSTALL_DIR:-}"
    _OVERRIDE_IMAGE_TAG="${IMAGE_TAG:-}"
    _OVERRIDE_DOMAIN="${DOMAIN:-}"
    _OVERRIDE_EMAIL="${CERT_EMAIL:-}"

    . "$_ENV_FILE"

    # 恢复被 server.env 覆盖的环境变量
    [ -n "$_OVERRIDE_IP" ]         && SERVER_IP="$_OVERRIDE_IP"
    [ -n "$_OVERRIDE_USER" ]       && SERVER_USER="$_OVERRIDE_USER"
    [ -n "$_OVERRIDE_INSTALL_DIR" ] && INSTALL_DIR="$_OVERRIDE_INSTALL_DIR"
    [ -n "$_OVERRIDE_IMAGE_TAG" ]  && IMAGE_TAG="$_OVERRIDE_IMAGE_TAG"
    [ -n "$_OVERRIDE_DOMAIN" ]     && DOMAIN="$_OVERRIDE_DOMAIN"
    [ -n "$_OVERRIDE_EMAIL" ]      && CERT_EMAIL="$_OVERRIDE_EMAIL"

    unset _OVERRIDE_IP _OVERRIDE_USER _OVERRIDE_INSTALL_DIR \
          _OVERRIDE_IMAGE_TAG _OVERRIDE_DOMAIN _OVERRIDE_EMAIL
fi
