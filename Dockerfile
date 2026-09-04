# 运行镜像（直接使用外部构建好的 JAR）
# 构建前先在本地执行：mvn clean package -DskipTests
FROM docker.m.daocloud.io/library/eclipse-temurin:21-jre-jammy
WORKDIR /app

# 安装 PostgreSQL 客户端（pg_dump / pg_restore）
RUN apt-get update \
    && apt-get install -y --no-install-recommends postgresql-client \
    && rm -rf /var/lib/apt/lists/*

# 创建运行用户（非 root 运行）
RUN groupadd -r panjia && useradd -r -g panjia panjia

# 复制 JAR（本地构建好的）
COPY customer/target/panjia-console-customer.jar /app/panjia-console.jar

# JKS 密钥目录（需外部挂载，不打包进镜像）
# 示例：-v ./script/keys/panjia-license.jks:/app/keys/panjia-license.jks:ro
RUN mkdir -p /app/keys && chown panjia:panjia /app/keys

# 备份目录
RUN mkdir -p /app/backups && chown panjia:panjia /app/backups

USER panjia

ENV JAVA_OPTS="-Xms512m -Xmx1024m -XX:+UseG1GC"

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/panjia-console.jar"]
