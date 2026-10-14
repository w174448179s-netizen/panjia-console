# bin/frp —— FRP 内网穿透与本地整栈

把「云服务器 + 各客户本机」打通的一套脚本：盘家系统**整栈**部署在每个客户自己的机器上（deploy/docker：nginx 同源服务前端 + `/prod-api` + `/oss`，外加后端/PostgreSQL/Redis/MinIO），frpc 反向连到云服务器的 frps。**每个客户只开一条隧道**，外网通过 `http://panjia.icu:3000+N` 访问——页面 / API / 图片回显全部同源。

**0 号开发机与客户机完全同构**（本地也用 deploy/docker 的 nginx.conf + web nginx:80 + 单隧道 3000→80），测过的就是交付的。

## 架构（2026-10 终版：整栈一体 + 单隧道，全员一致）

```
客户浏览器
   │  http://panjia.icu:3000+N        (同源入口：页面/API/图片)
   ▼
云服务器 (panjia.icu)
   └─ panjia-frps 容器     7000/7500  frp 服务端（白名单 3000-3099 + 8080-8099）
                                        ▲ frpc 反向连接（纯 TCP 转发，零业务配置）
   ▼
本机（0 号开发机 / 客户机，形态完全一致）
   ├─ panjia-frpc 容器    远端 3000+N → 本机 80（一条隧道全包）
   └─ 整栈
         web nginx:80     前端 dist + /prod-api → server + /oss → minio（同源）
         server / postgres / redis / minio
```

> 0 号本地栈：中间件用 `panjia-server/script/docker/docker-compose.yml`
> （其 `web` 服务与客户 deploy/docker 同一份 nginx.conf，dist 挂载 panjia-ui/dist）；
> 后端容器由 `run-local-backend.sh` 以 `--network-alias server` 接入同一网络。
> **preview 多成员机制已退役**（panjia.icu 上 3100 共享 dist 站点已下线；
> `nginx-preview.conf` / `deploy-preview.sh` 留档不再使用）。

## 端口约定（第 N 号，N 从 0 开始）

| 用途 | 端口 | 落点 |
|---|---|---|
| 系统入口（同源：页面/API/图片） | **3000 + N** | 本机 nginx:80（0 号：script/docker 的 web；客户：deploy/docker 的 web） |
| 0 号本地后端直连 | 18080 | 本机 Docker 后端（nginx 经内网别名 `server` 直达，18080 仅供调试） |
| frps 控制 / 面板 | 7000 / 7500 | 服务器 |

- 容量上限 **N ≤ 99**（3000-3099 端口池）
- remotePort 先到先得，撞端口会注册失败并提示，天然防冲突
- 内外访问**同一形态**：内网 `http://本机IP/`，外网 `http://panjia.icu:3000+N/`

## 脚本一览

| 脚本 | 作用 | 用法 |
|---|---|---|
| `install-frps.sh` | 服务器上装 frps（Docker 容器常驻） | `sh bin/frp/install-frps.sh`；`--reset-config` 重置 token/面板密码 |
| `install-frpc.sh` | 本机装 frpc（Docker 容器常驻） | `sh bin/frp/install-frpc.sh`；`--reset-config` 重写 |
| `pack-frpc.sh` | 给新客户打「一键接入包」 | `sh bin/frp/pack-frpc.sh <客户名> <3000+N>`（单端口） |
| `run-local-backend.sh` | 0 号后端 Docker 化（别名 server 接入本地网络） | `sh bin/frp/run-local-backend.sh [--skip-build \| --stop]` |
| `deploy-preview.sh` / `nginx-preview.conf` | 【已退役】preview 多成员机制留档 | 不再使用 |

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

## 0 号开发机日常（与客户同构）

```bash
# 后端改动 → 重建本地 Docker 后端（与客户交付物同一镜像、同一 nginx、同一形态）
sh bin/frp/run-local-backend.sh            # Maven 打包 + 建镜像 + 起容器
sh bin/frp/run-local-backend.sh --skip-build   # 不重打包

# 前端改动 → 构建 dist 即时生效（web nginx 挂载 dist，无需重启）
cd panjia-ui && pnpm build

# IDE 开发（vite 已挪到 5173，不与 80 冲突）
cd panjia-ui && pnpm dev                   # http://localhost:5173，/prod-api 仍代理 IDE 8080

# 验证三条链路（本机）
curl -s -o /dev/null -w '%{http_code}\n' http://localhost/
curl -s -o /dev/null -w '%{http_code}\n' http://localhost/prod-api/auth/tenant/list
curl -s -o /dev/null -w '%{http_code}\n' http://localhost/oss/minio/health/live

# 验证外网（经 frp）
curl -s -o /dev/null -w '%{http_code}\n' http://panjia.icu:3000/
```

- 数据库分离：Docker 后端用 `panjia_test` 测试库，IDE 开发用开发库（`application-docker-local.yml`）；
- Docker 后端重建后 run 脚本会自动挂 `--network docker_default --network-alias server`；
- 0 号的 frpc 配置 `bin/frp/frpc.toml` 为手工维护（单隧道 panjia-app 3000→80）。

## 配置文件

| 文件 | 说明 |
|---|---|
| `frpc.toml` | 0 号开发机 frpc 配置（手工维护：单隧道 panjia-app 3000→80；含 token，不入 Git） |
| `nginx-preview.conf` | 【已退役】旧 preview 站点模板，留档 |
| `firewall-rules.csv` | 腾讯云安全组放行清单 |

## 注意

- frp 版本三处保持一致：`install-frps.sh` / `install-frpc.sh` / `pack-frpc.sh` 里的 `FRP_VERSION`。
- `frpc.toml`、分发包 zip 都含 token，不要提交 Git、不要外传。
- OSS 配置三通道口径见 `panjia-server/deploy/docker/OSS_SETUP.md`（endpoint 统一 `minio:9000`；domain_url = 各自入口 + `/oss`）。
