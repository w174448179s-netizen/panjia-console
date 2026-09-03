# 构建阶段
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /app

# 复制父 pom 和各模块 pom（利用 Docker 缓存）
COPY pom.xml .
COPY common/pom.xml common/pom.xml
COPY license/pom.xml license/pom.xml
COPY customer/pom.xml customer/pom.xml

RUN mvn dependency:go-offline -B -pl customer -am

# 复制源码
COPY common/src common/src
COPY license/src license/src
COPY customer/src customer/src

# 构建（只构建 customer 模块及其依赖）
RUN mvn package -DskipTests -B -pl customer -am

# 运行阶段
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# 创建运行用户（非 root 运行）
RUN groupadd -r panjia && useradd -r -g panjia panjia

# 复制 JAR（从 customer 模块 target 目录复制）
COPY --from=builder /app/customer/target/panjia-console-customer.jar /app/panjia-console.jar

# ★ H5：JKS 文件需以只读卷挂载，权限 600
#   不打包进镜像，通过 docker volume 或 bind mount 注入
#   示例：-v /path/to/panjia-license.jks:/app/keys/panjia-license.jks:ro

# 设置目录权限
RUN mkdir -p /app/keys && chown panjia:panjia /app/keys

USER panjia

# JVM 参数
ENV JAVA_OPTS="-Xms512m -Xmx1024m -XX:+UseG1GC"

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/panjia-console.jar"]
