# 盘家智管授权服务 (panjia-console)

单一 Spring Boot 应用，同进程承载运营操作台（customer）和授权引擎（license）。

## 架构

```
panjia-console/
├── customer/    # 运营操作台 + 看板（管理面，内网/VPN）
├── license/     # 授权引擎（鉴权面，公网 443）
└── common/      # 共用：异常、DTO、工具、枚举
```

- 两模块通过 `LicenseEngine` 接口方法调用协作，不走 HTTP
- 包边界铁律：customer 只允许通过 `LicenseEngine` 调用 license

## 快速开始

### 1. 生成密钥对

```bash
chmod +x bin/gen_keypair.sh
./bin/gen_keypair.sh YourStrongPassword123
```

### 2. 配置环境变量

```bash
cp .env.example .env
# 编辑 .env 设置实际密码
```

### 3. 启动服务

```bash
# 开发模式（仅数据库）
docker compose up -d postgres redis

# 后端运行
mvn spring-boot:run

# 前端运行
cd ui
pnpm install
pnpm dev
```

### 4. 生产部署

```bash
# 构建前端
cd ui && pnpm build && cd ..

# 全栈启动
docker compose up -d
```

## 安全要点

| 项 | 说明 |
|---|---|
| 管理面防护 | 仅内网/VPN，Nginx allow/deny + deny all 兜底 |
| 私钥 | JKS 权限 600，密码环境变量注入，不出进程 |
| 审计 | 所有操作记录 pj_ops_log（input/before/after） |
| 鉴权面 | 公网 443，JWT RS256 + 基础限流 |

## 文档

详细设计见：`盘家智管_授权服务详细设计_V1.4.md`
