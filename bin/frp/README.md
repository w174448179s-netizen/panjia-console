# bin/frp —— FRP 内网穿透与 preview 部署

把「云服务器 + 各客户本机」打通的一套脚本：盘家系统**整栈**部署在每个客户自己的机器上（nginx + 后端 + MinIO），frpc 反向连到云服务器的 frps。前端构建产物**全员共享**在云服务器上，外网通过 `http://panjia.icu:3100+N` 访问——**页面 / API / 图片回显全部同源，一个入口**。

## 架构（2026-10 改造后）

```
客户浏览器
   │  http://panjia.icu:3100+N        (preview 站点，同源入口)
   ▼
云服务器 (panjia.icu)
   ├─ panjia-preview 容器  3100-3199  nginx：
   │     /           → 共享 dist（前端静态，全员同一份）
   │     /prod-api/  → host.docker.internal:8080+N  (frp 隧道 → 客户机后端)
   │     /minio/     → host.docker.internal:3010+N  (frp 隧道 → 客户机 MinIO)
   └─ panjia-frps 容器     7000/7500  frp 服务端（白名单 3000-3099 + 8080-8099）
                                        ▲ frpc 反向连接
客户本机                                 │
   ├─ panjia-frpc 容器    ──────────────┘
   │     panjia-api:   远端 8080+N → 本机 8080 (后端；本机 Docker 部署为 18080)
   │     panjia-minio: 远端 3010+N → 本机 9000 (MinIO)
   └─ deploy/docker 整栈（nginx:80 / server:8080 / postgres / redis / minio:9000）
```

> 历史包袱已拆除：旧「ui 隧道 3000+N → 本机 vite」已删除（3000 端口空出）。
> 图片回显不依赖任何开发进程；3100 的 `/minio/health/live` 返回 403 属 MinIO 探测路径差异，对象访问正常。

## 端口约定（第 N 号成员，N 从 0 开始；0 号 = 管理员自己）

| 用途 | 端口 | 落点 |
|---|---|---|
| 系统入口（同源：页面/API/图片） | **3100 + N** | 服务器 preview nginx 直出共享 dist |
| api 隧道 | 8080 + N | 客户本机后端 8080（本机 Docker 部署为 18080） |
| minio 隧道 | 3010 + N | 客户本机 MinIO 9000 |
| frps 控制 / 面板 | 7000 / 7500 | 服务器 |

- 容量上限 **N ≤ 19**（受 frps 白名单 8080-8099 限制；minio 白名单 3000-3099 够到 89）
- remotePort 先到先得，撞端口会注册失败并提示，天然防冲突

## 脚本一览

### 服务器侧（管理员执行）

| 脚本 | 作用 | 用法 |
|---|---|---|
| `install-frps.sh` | 服务器上装 frps（Docker 容器常驻） | `sh bin/frp/install-frps.sh`；`--reset-config` 重置 token/面板密码 |
| `deploy-preview.sh` | 发布前端 dist + 生成成员 nginx 站点（自动 reload） | `sh bin/frp/deploy-preview.sh [N] [--build]`；不带 `--build` 只同步已有 dist |

服务器落点：`/etc/frp/frps.toml`、`/opt/panjia-frp/docker-compose.yml`、`/opt/panjia-preview/{conf,dist}`。
腾讯云安全组放行：`firewall-rules.csv`（7000 / 7500 / 3000-3099 / 8080-8099 / 3100-3199）。

### 客户接入（管理员打包 → 客户一键接入）

| 脚本 | 作用 | 用法 |
|---|---|---|
| `install-frpc.sh` | 本机装 frpc（Docker 容器常驻） | `sh bin/frp/install-frpc.sh`；`--reset-config` 重写；`--gen-user` 只生成分发包 |
| `pack-frpc.sh` | 给新客户打「一键接入包」 | `sh bin/frp/pack-frpc.sh <客户名> <8080+N> <3010+N>`（脚本会校验两个端口是同一个 N） |

## 新客户接入流程（第 N 号）

**管理员（Mac）三步：**

```bash
sh bin/frp/pack-frpc.sh <客户名> <8080+N> <3010+N>   # 1. 打包 frpc 接入包（zip 发给客户）
sh bin/frp/deploy-preview.sh <N>                     # 2. 生成该客户的 preview 站点
# 3. 腾讯云安全组放行 3100+N/TCP（若未放行整段 3100-3199）
```

**客户（本机）三步（接入包 README.txt 里也有）：**

1. 标准 `panjia-server/deploy/docker` 整栈部署，**给 server 服务加 `ports: "8080:8080"`**（frp 从宿主机进后端）；
   确保 `sys_oss_config`：`endpoint=minio:9000`、`domain_url=panjia.icu:3100+N/minio`（或跑 `deploy/docker/fix-oss.sh` 自动配）
2. 解压接入包，跑 `sh install-frpc.sh`（Windows 双击 `.bat`）——出现 `login to server success` 即接入
3. 浏览器打开 `http://panjia.icu:3100+N` 验证：页面能开、登录后传一张图能回显

> 前置：客户机宿主机 8080（后端）、9000（MinIO）在监听。Linux 客户机注意：compose 里
> 绑定 `127.0.0.1` 的端口 frpc（host-gateway 方式）够不到，需改为 `"9000:9000"` 全接口发布。

## 本机后端 Docker 化（0 号成员，IDE 与远程访问互不冲突）

| 脚本 | 作用 | 用法 |
|---|---|---|
| `run-local-backend.sh` | 把后端打包成 Docker 容器跑在 18080 | `sh bin/frp/run-local-backend.sh [--skip-build \| --stop]` |

- 远程 preview 走 frp → 宿主机 **18080**（Docker 后端，打包版）；
- IDE 开发继续占 **8080**，两边完全独立；
- **数据库分离**：Docker 后端用 `panjia_test` 测试库，IDE 开发用 `postgres` 开发库（同一 PG 实例两个库，Flyway 首次启动自动建表）；
- 切换 frp 指向：改 `frpc.toml` 里 `panjia-api` 的 `localPort`（18080=Docker / 8080=IDE），然后 `docker restart panjia-frpc`；
- Docker 后端通过 `host.docker.internal` 连本机的 postgres/redis/minio（profile：`dev,docker-local`，覆盖配置在 `panjia-server/ruoyi-admin/src/main/resources/application-docker-local.yml`）；
- 本机后端容器若重建过，需补一句 `docker network connect docker_default panjia-local-backend`（详见 deploy/docker/OSS_SETUP.md）。

## 配置文件

| 文件 | 说明 |
|---|---|
| `frpc.toml` | 本机 frpc 配置（含 token，**不入 Git**）；`localIP = host.docker.internal` |
| `nginx-preview.conf` | preview 站点模板，`__WEB_PORT__`/`__API_PORT__`/`__MINIO_PORT__`（=3100+N / 8080+N / 3010+N）由 deploy-preview.sh 按成员号替换 |
| `firewall-rules.csv` | 腾讯云安全组放行清单 |

## 日常操作速查

```bash
# 前端改动，全员 preview 同步更新
pnpm build && sh bin/frp/deploy-preview.sh

# 后端改动，让远程 preview 用上新代码（本地 Docker 后端重建）
sh bin/frp/run-local-backend.sh

# 查看 frpc 状态 / 日志
docker ps --filter name=panjia-frpc
docker logs -f panjia-frpc

# 验证 0 号成员三条链路
curl http://panjia.icu:3100/                                  # 页面
curl http://panjia.icu:3100/prod-api/auth/tenant/list         # API
curl http://panjia.icu:3100/minio/panjia-file/<任一文件路径>    # 图片（health 路径 403 属正常）
```

## 注意

- frp 版本三处保持一致：`install-frps.sh` / `install-frpc.sh` / `pack-frpc.sh` 里的 `FRP_VERSION`。
- `frpc.toml`、分发包 zip 都含 token，不要提交 Git、不要外传。
- `deploy-preview.sh` 的 dist 全员共享，谁构建发布其他人站点内容也会变。
- OSS 配置三通道口径见 `panjia-server/deploy/docker/OSS_SETUP.md`（endpoint 三环境统一 `minio:9000`；domain_url = 各自入口 + frp 模式 `/minio`、docker 模式 `/oss`）。
