#!/bin/bash
#
# 构建 panjia-console（后端 Docker 镜像 + 前端静态文件）
# 用法：
#   sh bin/build.sh [镜像标签]              # 构建后端镜像 + 前端静态文件
#   sh bin/build.sh [镜像标签] --backend    # 只构建后端
#   sh bin/build.sh [镜像标签] --frontend   # 只构建前端
# 默认标签：latest
#

set -e

IMAGE_TAG="${1:-latest}"
PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"

BUILD_BACKEND=true
BUILD_FRONTEND=true

if [ "$2" = "--backend" ]; then
    BUILD_FRONTEND=false
elif [ "$2" = "--frontend" ]; then
    BUILD_BACKEND=false
fi

BACKEND_IMAGE="panjia-console:${IMAGE_TAG}"

echo "=========================================="
echo "  构建"
echo "  标签：$IMAGE_TAG"
if [ "$BUILD_BACKEND" = true ]; then
    echo "  后端镜像：$BACKEND_IMAGE"
fi
if [ "$BUILD_FRONTEND" = true ]; then
    echo "  前端：npm run build → ui/dist/"
fi
echo "=========================================="

# 1. 后端：Maven 打包 + Docker 镜像
if [ "$BUILD_BACKEND" = true ]; then
    echo ""
    echo "[后端 1/2] Maven 打包..."
    cd "$PROJECT_DIR"

    if [ -z "$JAVA_HOME" ]; then
        if [ -d "/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home" ]; then
            export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
            echo "[INFO] 自动检测 JAVA_HOME: $JAVA_HOME"
        else
            echo "[ERROR] 未设置 JAVA_HOME，且未检测到 temurin-21"
            exit 1
        fi
    fi

    mvn clean package -DskipTests -q

    if [ ! -f "customer/target/panjia-console-customer.jar" ]; then
        echo "[ERROR] 打包失败，未找到 customer/target/panjia-console-customer.jar"
        exit 1
    fi
    echo "  ✓ Maven 打包完成"

    echo ""
    echo "[后端 2/2] 构建 Docker 镜像..."
    docker build -t "$BACKEND_IMAGE" .
    echo "  ✓ 后端镜像构建完成：$BACKEND_IMAGE"
fi

# 2. 前端：npm run build
if [ "$BUILD_FRONTEND" = true ]; then
    echo ""
    echo "[前端] npm run build..."
    cd "$PROJECT_DIR/ui"

    if [ ! -d node_modules ]; then
        echo "  安装依赖..."
        npm install
    fi

    npm run build

    if [ ! -d dist ]; then
        echo "[ERROR] 前端构建失败，未找到 ui/dist/"
        exit 1
    fi

    echo "  ✓ 前端构建完成：ui/dist/（$(du -sh dist | cut -f1)）"
fi

echo ""
echo "=========================================="
echo "  ✓ 全部构建完成"
echo "=========================================="
if [ "$BUILD_BACKEND" = true ]; then
    echo "  后端镜像：$BACKEND_IMAGE"
fi
if [ "$BUILD_FRONTEND" = true ]; then
    echo "  前端文件：$PROJECT_DIR/ui/dist/"
fi
echo ""
echo "本地启动：sh bin/start.sh $IMAGE_TAG"
echo "发布到服务器：sh bin/deploy-to-server.sh <服务器IP> $IMAGE_TAG"
