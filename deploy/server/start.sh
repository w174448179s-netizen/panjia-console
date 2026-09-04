#!/bin/bash
# 启动服务
cd "$(dirname "$0")"
docker compose up -d
echo "服务已启动，查看日志：./logs.sh"
