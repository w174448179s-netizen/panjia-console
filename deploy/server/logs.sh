#!/bin/bash
# 查看日志
# 用法：./logs.sh           # 实时跟踪
#       ./logs.sh --tail 200
cd "$(dirname "$0")"
if [ $# -eq 0 ]; then
    docker compose logs -f --tail 100
else
    docker compose logs "$@"
fi
