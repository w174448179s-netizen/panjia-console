#!/bin/bash
# 重启服务
cd "$(dirname "$0")"
docker compose restart
echo "服务已重启，查看日志：./logs.sh"
