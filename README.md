# 盘家智管授权服务 (panjia-console)

单一 Spring Boot 4.1.0 应用（JDK 21），同进程承载运营操作台（customer）和授权引擎（license）。

## 架构

```
panjia-console/
├── common/      # 共用：异常、DTO、工具、枚举、JsonbTypeHandler
├── license/     # 授权引擎（鉴权面，公网 443）：JWT 签发/验签、指纹绑定、心跳
├── customer/    # 运营操作台 + 看板（管理面，内网/VPN）：启动模块
├── ui/          # Vue3 + Element Plus 前端
├── script/keys/ # JKS 密钥库（不入 Git，权限 600）
└── bin/         # 运维脚本
```

- 三模块通过 Maven 单向依赖：`customer → license → common`
- `customer` 与 `license` 同进程内通过 Java 方法调用协作，不走 HTTP
- 包边界铁律：customer 只允许通过 `LicenseEngine` 接口调用 license

## 技术栈

| 组件 | 版本 |
|---|---|
| JDK | 21 |
| Spring Boot | 4.1.0 |
| MyBatis-Plus | 3.5.17（`mybatis-plus-spring-boot4-starter`） |
| Flyway | 10.x（随 Boot 4 管理） |
| PostgreSQL | 16 |
| 前端 | Vue3 + Element Plus 2.8.0 |
| 定时任务 | Spring `@Scheduled` |

> **无 Redis 依赖**，所有状态由 PostgreSQL 承载。

## 快速开始

### 1. 生成密钥对

```bash
chmod +x bin/gen_keypair.sh
./bin/gen_keypair.sh YourStrongPassword123
```

生成 `script/keys/panjia-license.jks`（私钥库）和 `script/keys/public-key.pem`（公钥）。

### 2. 启动数据库

```bash
docker compose up -d postgres
```

数据库配置：`postgres/root/root`，端口 5432。

### 3. 启动后端

```bash
# 开发环境（使用 application-dev.yml，JKS 密码默认 devpass123456）
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
mvn spring-boot:run -pl customer

# 生产环境
export PANJIA_JKS_PASSWORD='YourStrongPassword123'
export PANJIA_KEY_PASSWORD='YourStrongPassword123'
java -jar customer/target/panjia-console-customer.jar
```

后端运行在 `http://localhost:8081`。

### 4. 启动前端

```bash
cd ui
npm install
npm run dev
```

前端运行在 `http://localhost:3000`，API 代理到 `http://localhost:8081`。

### 5. 生产部署

```bash
# 构建前端
cd ui && npm run build && cd ..

# 构建后端
mvn clean package -DskipTests

# 启动（Docker）
docker compose up -d
```

## API 路由

| 前缀 | 访问面 | 说明 |
|---|---|---|
| `/api/auth/*` | 公网 443 | 激活 / 心跳 / 校验 |
| `/api/v1/*` | 内网/VPN | 管理后台 API |

### 鉴权面接口（对齐 panjia-server 客户端）

| 接口 | 方法 | 认证方式 | 说明 |
|---|---|---|---|
| `/api/auth/activate` | POST | 无 | 激活授权，返回 JWT |
| `/api/auth/heartbeat` | POST | `Authorization: Bearer <jwt>` | 心跳上报 |
| `/api/auth/check` | POST | `Authorization: Bearer <jwt>` | 实时授权校验 |

> `fingerprint` 字段传 SHA-256 哈希字符串，不是对象。
> JWT 通过 `Authorization: Bearer` 请求头传递，不在 body 中。

### 管理面接口（内网）

| 接口 | 说明 |
|---|---|
| `/api/v1/customers` | 客户管理 |
| `/api/v1/license-mgmt/*` | 授权签发/吊销/恢复/换机 |
| `/api/v1/alerts` | 告警管理 |
| `/api/v1/dashboard/*` | 看板统计 |
| `/api/v1/backups` | 备份管理 |
| `/api/v1/upgrades` | 升级管理 |

## Spring Boot 4 适配要点

1. **Starter 细粒度化**：使用 `spring-boot-starter-webmvc`（不是 `spring-boot-starter-web`）；
2. **Jackson 显式声明**：需 `spring-boot-starter-json` + `jackson-databind`（compile scope）；
3. **Flyway 需 JDBC**：添加 `spring-boot-starter-jdbc` 触发 DataSource 自动配置；
4. **编译参数**：`maven-compiler-plugin` 加 `<parameters>true</parameters>`；
5. **jsonb 类型**：使用自定义 `JsonbTypeHandler` 处理 PostgreSQL jsonb 列。

## 安全要点

| 项 | 说明 |
|---|---|
| 管理面防护 | 仅内网/VPN，Nginx allow/deny 兜底，无应用层认证 |
| 私钥 | JKS 权限 600，密码环境变量 `PANJIA_JKS_PASSWORD` 注入，不出进程 |
| 审计 | 所有操作记录 `pj_ops_log` |
| 鉴权面 | 公网 443，JWT RS256 + Nginx 限流 |
| 状态码 | 授权受限永远 200 + `clientMode=RESTRICT`，绝不 401/403 |

## 文档

详细设计见：`docs/盘家智管_授权服务详细设计_V1.6.md`
