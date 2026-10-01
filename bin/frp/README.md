# bin/frp —— FRP 内网穿透与 preview 部署

把「云服务器 + 各成员本机」打通的一套脚本：成员本机的 panjia 系统通过 frpc 反向连到云服务器的 frps，外网经 `http://panjia.icu:端口` 访问。

## 架构

```
客户浏览器
   │  http://panjia.icu:3100+N        (preview 站点，nginx 静态直出)
   ▼
云服务器
   ├─ panjia-preview 容器  3100-3199  nginx：静态 dist + /prod-api → 宿主机 8080+N
   └─ panjia-frps 容器     7000/7500  frp 服务端
                                        ▲ frpc 反向连接
成员本机 (Mac)                           │
   ├─ panjia-frpc 容器    ──────────────┘
   │     panjia-web: 远端 3000+N → 本机 80    (panjia-ui vite，备用慢链路)
   │     panjia-api: 远端 8080+N → 本机 18080 (Docker 后端) 或 8080 (IDE)
   ├─ panjia-local-backend 容器  18080   后端 Docker 版（供远程访问）
   └─ IDE 直接跑的后端            8080    本地开发调试用
```

## 端口约定（第 N 号成员，N 从 0 开始）

| 用途 | 远端端口 | 本机落点 |
|---|---|---|
| preview 站点（客户访问） | 3100+N | 服务器 nginx 直出共享 dist |
| api 隧道 | 8080+N | 本机 18080（Docker 后端）/ 8080（IDE 后端） |
| ui 备用隧道（慢） | 3000+N | 本机 80（vite dev） |
| frps 控制 / 面板 | 7000 / 7500 | 服务器 |

## 脚本一览

### 服务器侧（管理员执行一次）

| 脚本 | 作用 | 用法 |
|---|---|---|
| `install-frps.sh` | 服务器上装 frps（Docker 容器常驻） | `sh bin/frp/install-frps.sh`；`--reset-config` 重置 token/面板密码 |
| `deploy-preview.sh` | 发布前端 dist 到服务器 + 生成成员 nginx 站点 | `sh bin/frp/deploy-preview.sh [N] [--build]`；不带 `--build` 只同步已有 dist |

服务器落点：`/etc/frp/frps.toml`、`/opt/panjia-frp/docker-compose.yml`、`/opt/panjia-preview/{conf,dist}`。
云防火墙需放行：`firewall-rules.csv`（7000 / 7500 / 3000-3099 / 8080-8099 / 3100-3199）。

### 成员接入

| 脚本 | 作用 | 用法 |
|---|---|---|
| `install-frpc.sh` | 本机装 frpc（Docker 容器常驻） | `sh bin/frp/install-frpc.sh`；`--reset-config` 重写 frpc.toml；`--gen-user` 只生成分发包 |
| `pack-frpc.sh` | 给新成员打「一键接入包」 | `sh bin/frp/pack-frpc.sh zhangsan 3001 8081`（端口=3000+N / 8080+N） |

新成员接入两步：
```bash
sh bin/frp/pack-frpc.sh <名字> <3000+N> <8080+N>   # 打分发包，发给成员
sh bin/frp/deploy-preview.sh <N>                   # 服务器开 preview 站点
```
成员侧拿到 zip 解压后跑包里的 `install-frpc.sh`（Mac/Linux）或 `.bat`（Windows）即可。

### 本机后端 Docker 化（IDE 与远程访问互不冲突）

| 脚本 | 作用 | 用法 |
|---|---|---|
| `run-local-backend.sh` | 把后端打包成 Docker 容器跑在 18080 | `sh bin/frp/run-local-backend.sh [--skip-build \| --stop]` |

- 远程 preview 走 frp → 宿主机 **18080**（Docker 后端，打包版）；
- IDE 开发继续占 **8080**，两边完全独立；
- **数据库分离**：Docker 后端用 `panjia_test` 测试库，IDE 开发用 `postgres` 开发库（同一 PG 实例两个库，Flyway 首次启动自动建表）；
- 切换 frp 指向：改 `frpc.toml` 里 `panjia-api` 的 `localPort`（18080=Docker / 8080=IDE），然后 `docker restart panjia-frpc`；
- Docker 后端通过 `host.docker.internal` 连本机的 postgres/redis/minio（profile：`dev,docker-local`，覆盖配置在 `panjia-server/ruoyi-admin/src/main/resources/application-docker-local.yml`）。

## 配置文件

| 文件 | 说明 |
|---|---|
| `frpc.toml` | 本机 frpc 配置（含 token，**不入 Git**）；`localIP = host.docker.internal`（frpc 容器内访问宿主机） |
| `nginx-preview.conf` | preview 站点模板，`__WEB_PORT__`/`__API_PORT__` 由 deploy-preview.sh 按成员号替换 |
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

# 验证 api 隧道是否通（成员 0）
curl http://panjia.icu:8080/auth/tenant/list
```

## 注意

- frp 版本三处保持一致：`install-frps.sh` / `install-frpc.sh` / `pack-frpc.sh` 里的 `FRP_VERSION`。
- `frpc.toml`、分发包 zip 都含 token，不要提交 Git、不要外传。
- `deploy-preview.sh` 的 dist 全员共享，谁构建发布其他人站点内容也会变。
