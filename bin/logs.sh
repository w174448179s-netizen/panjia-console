#!/bin/bash
#
# 查看 panjia-console 容器日志
# 用法：
#   sh bin/logs.sh              # 查看后端日志（默认）
#   sh bin/logs.sh web          # 查看前端日志
#   sh bin/logs.sh -n 200       # 查看后端最后 200 行
#   sh bin/logs.sh web -f       # 实时跟踪前端日志
#

set -e

BACKEND_CONTAINER="panjia-console"
FRONTEND_CONTAINER="panjia-console-web"

# 判断第一个参数是不是 web
if [ "$1" = "web" ]; then
    shift
    CONTAINER="$FRONTEND_CONTAINER"
else
    CONTAINER="$BACKEND_CONTAINER"
fi

# 检查容器是否存在
if ! docker ps -a -q --filter "name=$CONTAINER" | grep -q .; then
    echo "[ERROR] 容器 $CONTAINER 不存在"
    exit 1
fi

# 默认实时跟踪最后 100 行
if [ $# -eq 0 ]; then
    docker logs -f --tail 100 "$CONTAINER"
else
    docker logs "$@" "$CONTAINER"
fi
