# bin/frp —— FRP 内网穿透与 preview 部署

把「云服务器 + 各客户本机」打通的一套脚本：盘家系统**整栈**部署在每个客户自己的机器上（deploy/docker：nginx 同源服务前端 + `/prod-api` + `/oss`，外加后端/PostgreSQL/Redis/MinIO），frpc 反向连到云服务器的 frps。**每个客户只开一条隧道**，外网通过 `http://panjia.icu:3000+N` 访问——页面 / API / 图片回显全部同源。

## 架构（2026-10 终版：整栈一体 + 单隧道）

```
客户浏览器
   │  http://panjia.icu:3000+N        (同源入口：页面/API/图片)
   ▼
云服务器 (panjia.icu)
   ├─ panjia-frps 容器     7000/7500  frp 服务端（白名单 3000-3099 + 8080-8099）
   │                                    ▲ frpc 反向连接（纯 TCP 转发，零业务配置）
   ├─ panjia-preview 容器  3100        【仅 0 号开发机】共享 dist + /prod-api + /minio
   │                                   反代（依赖 0 号的 api/minio 双隧道）
   ▼
客户本机（第 N 号，N ≥ 1）
   ├─ panjia-frpc 容器    远端 3000+N → 本机 80（一条隧道全包）
   └─ deploy/docker 整栈
         nginx:80        前端 dist + /prod-api → server + /oss → minio（同源）
         server / postgres / redis / minio
```

> 演进记录：v1 三隧道（ui 3000+N / api 8080+N / minio 3010+N + 每客户 preview 站点）
> → v2 双隧道（api + minio）→ **终版单隧道**（前端部署到客户机，一个端口全包，
> preview 多成员机制退役；3100 仅供 0 号开发机使用）。3000-3099 白名单复用为
> 客户入口端口池，无需改 frps。

## 端口约定（第 N 号客户，N 从 0 开始）

| 用途 | 端口 | 落点 |
|---|---|---|
| 客户系统入口（同源：页面/API/图片） | **3000 + N** | 客户机 nginx:80（deploy/docker 整栈，`WEB_PORT` 对外发布） |
| 0 号开发机 preview | 3100 | 服务器 preview nginx（依赖 0 号本机 api 8080 / minio 3010 双隧道） |
| 0 号开发机隧道 | 8080 / 3010 | 本机 18080(Docker 后端)/8080(IDE) / MinIO 9000 |
| frps 控制 / 面板 | 7000 / 7500 | 服务器 |

- 客户容量上限 **N ≤ 99**（3000-3099 端口池；8080-8099 白名单仅供 0 号开发机隧道，可择机收紧）
- remotePort 先到先得，撞端口会注册失败并提示，天然防冲突
- 客户内外访问**同一形态**：内网直接 `http://客户机IP/`，外网 `http://panjia.icu:3000+N/`

## 脚本一览

### 服务器侧（管理员执行）

| 脚本 | 作用 | 用法 |
|---|---|---|
| `install-frps.sh` | 服务器上装 frps（Docker 容器常驻） | `sh bin/frp/install-frps.sh`；`--reset-config` 重置 token/面板密码 |
| `deploy-preview.sh` | 发布前端 dist + 0 号 preview 站点（自动 reload） | `sh bin/frp/deploy-preview.sh [--build]` |

服务器落点：`/etc/frp/frps.toml`、`/opt/panjia-frp/docker-compose.yml`、`/opt/panjia-preview/{conf,dist}`。
腾讯云安全组放行：`firewall-rules.csv`（7000 / 7500 / 3000-3099 / 8080-8099 / 3100-3199）。

### 客户接入（管理员打包 → 客户一键接入）

| 脚本 | 作用 | 用法 |
|---|---|---|
| `install-frpc.sh` | 本机装 frpc（Docker 容器常驻） | `sh bin/frp/install-frpc.sh`；`--reset-config` 重写（**0 号双隧道为手工维护，重置会覆盖，慎用**）；`--gen-user` 只生成分发包 |
| `pack-frpc.sh` | 给新客户打「一键接入包」 | `sh bin/frp/pack-frpc.sh <客户名> <3000+N>`（单端口） |

## 新客户接入流程（第 N 号）

**管理员（Mac）两步：**

```bash
sh bin/frp/pack-frpc.sh <客户名> <3000+N>   # 1. 打包 frpc 接入包（zip 发给客户）
# 2. 腾讯云安全组放行 3000+N/TCP（若未放行整段 3000-3099）
```

**客户（本机）三步（接入包 README.txt 里也有）：**

1. 标准 `panjia-server/deploy/docker` 整栈部署（`WEB_PORT` 对外发布，如 `"80:80"`）；
   系统里配置 `domain_url = panjia.icu:3000+N/oss`（「文件配置管理」minio 行，或跑 `deploy/docker/fix-oss.sh` 自动配）
2. 解压接入包，跑 `sh install-frpc.sh`（Windows 双击 `.bat`）——出现 `login to server success` 即接入
3. 浏览器打开 `http://panjia.icu:3000+N` 验证：页面能开、登录后传一张图能回显

> 客户机**不需要**给 server 加 `ports: "8080:8080"`——nginx 到 server/minio 走 compose
> 内网，frp 只碰 nginx 的 80。Linux 客户机注意：`WEB_PORT` 映射须是 `0.0.0.0` 全接口
> （默认就是），frpc 容器经 host-gateway 才够得到。
>
> **前端/后端版本永远一体发布**：升级 = 给客户发新 deploy 包（含 dist），杜绝
> 「服务器前端新、客户后端旧」的接口错位。

## 本机后端 Docker 化（0 号开发机，IDE 与远程访问互不冲突）

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
| `frpc.toml` | 0 号开发机 frpc 配置（**手工维护**：api 8080 / minio 3010 双隧道 + preview 3100 依赖；含 token，不入 Git） |
| `nginx-preview.conf` | 0 号 preview 站点模板（`__WEB_PORT__`/`__API_PORT__`/`__MINIO_PORT__`） |
| `firewall-rules.csv` | 腾讯云安全组放行清单 |

## 日常操作速查

```bash
# 0 号 preview 前端更新
pnpm build && sh bin/frp/deploy-preview.sh

# 0 号后端改动，让远程 preview 用上新代码（本地 Docker 后端重建）
sh bin/frp/run-local-backend.sh

# 查看 frpc 状态 / 日志
docker ps --filter name=panjia-frpc
docker logs -f panjia-frpc

# 验证 0 号 preview 三条链路
curl http://panjia.icu:3100/                                  # 页面
curl http://panjia.icu:3100/prod-api/auth/tenant/list         # API
curl http://panjia.icu:3100/minio/panjia-file/<任一文件路径>    # 图片（health 路径 403 属正常）

# 验证某客户入口（N=1）
curl http://panjia.icu:3001/ && curl http://panjia.icu:3001/oss/minio/health/live
```

## 注意

- frp 版本三处保持一致：`install-frps.sh` / `install-frpc.sh` / `pack-frpc.sh` 里的 `FRP_VERSION`。
- `frpc.toml`、分发包 zip 都含 token，不要提交 Git、不要外传。
- 0 号 preview 的 dist 全员共享时代已结束：preview 只服务 0 号自己；客户前端随各自 deploy 包走。
- OSS 配置三通道口径见 `panjia-server/deploy/docker/OSS_SETUP.md`（endpoint 统一 `minio:9000`；domain_url = 各自入口 + `/oss`；0 号 preview 例外为 `panjia.icu:3100/minio`）。
