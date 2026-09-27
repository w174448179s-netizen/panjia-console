# bin/ —— 本地开发与运维脚本

> **运行位置：工程师的 Mac 本地**。这些脚本**永远不**会传到服务器，也**不**会进 docker 镜像。

## 目录职责

### 构建与发布

| 脚本 | 用途 | 运行时机 |
|---|---|---|
| `build.sh` | 本地构建后端 Spring Boot jar + 前端 dist | 改完代码、准备部署前 |
| `publish.sh` | **一键发布**：backend + frontend + 健康检查 | 改完代码、想一次推到服务器并自动验证 |
| `push-backend.sh` | **只**推送后端（Java/Dockerfile/compose 改动） | 改完后端代码、跳过前端发布 |
| `push-frontend.sh` | **只**推送前端（Vue 改动） | 改完 Vue 代码、跳过后端发布 |
| `push-homepage.sh` | 推送官网首页（`ui/homepage/` → 服务器 `web/home`） | 改完官网静态页 |
| `push-nginx.sh` | 推送 nginx 配置并安全生效（备份→校验→回滚） | 改完 nginx.conf / .htpasswd |

### 服务器初始化

| 脚本 | 用途 | 运行时机 |
|---|---|---|
| `setup-server.sh` | 远程初始化服务器（装 docker、申请证书、部署） | 新服务器首次部署、域名/HTTPS 切换 |
| `add-domain.sh` | 给已部署的 IP 模式服务器加域名（stage 2） | 域名解析到位后 |

### FRP 内网穿透（`frp/`）

| 脚本 | 用途 | 运行时机 |
|---|---|---|
| `frp/install-frps.sh` | 云服务器以 Docker 容器跑 frps（`/opt/panjia-frp/` 编排，幂等），支持多成员同时接入 | 首次开启「外网访问本机服务」、升级 frp 版本 |
| `frp/install-frpc.sh` | 本机以 Docker 容器跑 frpc（幂等），把本机 panjia-ui(80)/panjia-server(8080) 挂到服务器 | 首次配置、frps 重置 token 后（加 `--reset-config`） |
| `frp/pack-frpc.sh` | 为成员打「一键接入包」zip（toml + 镜像 + Mac/Windows 启动脚本 + 说明） | 新成员接入（第 N 号成员 → 3000+N / 8080+N） |
| `frp/firewall-rules.csv` | 腾讯云轻量服务器「防火墙 → 导入规则」用的 CSV（放行 7000/7500/3000-3099/8080-8099/3100-3199），**必须选「追加导入」**，覆盖导入会清掉 22/80/443 | frps 装好后外网仍不通时 |
| `frp/deploy-preview.sh` | 把 panjia-ui 构建产物发布到服务器（全员共享同一份 dist，nginx 静态直出），客户访问 `panjia.icu:3100+N`（N=成员号，0 号即 3100）；API 经 `8080+N` 隧道回成员本机后端。前端改动：`pnpm build && sh bin/frp/deploy-preview.sh`；新成员：`deploy-preview.sh <N>` | 前端改动后 / 新成员接入 |
| `frp/nginx-preview.conf` | preview 站点模板（`__WEB_PORT__`/`__API_PORT__` 占位符），由 deploy-preview.sh 按成员号替换后上传 | 一般不单独使用 |

# 前端改动后（全员客户同步更新）
cd panjia-ui && pnpm build && sh ../panjia-console/bin/frp/deploy-preview.sh

# 新成员接入（第 N 号）
sh bin/frp/pack-frpc.sh <名字> <3000+N> <8080+N>   # 打包发给成员
sh bin/frp/deploy-preview.sh <N>                    # 服务器开站点

### 密钥与认证

| 脚本 | 用途 | 运行时机 |
|---|---|---|
| `gen_keypair.sh` | 生成 JKS 密钥对（首次部署、轮换） | 项目初始化、密钥到期前 |
| `rotate-auth-password.sh` | 轮换管理端 Basic Auth 密码 | 定期轮换、人员变动时 |

### 本地开发

| 脚本 | 用途 | 运行时机 |
|---|---|---|
| `start.sh` / `stop.sh` / `restart.sh` / `logs.sh` | **本地 docker run 控制**（拼装长参数） | 本地起开发环境 |
| `clean-test-data.sh` | 清空测试数据库（保留密钥版本表） | 测试数据清理 |

### 配置与公共库

| 文件 | 用途 |
|---|---|
| `server.env.example` | 服务器连接信息模板，复制为 `server.env` 后填入实际值 |
| `_server-env.sh` | 公共库脚本，被其他脚本 `source` 加载服务器配置，**不直接执行** |

## 为什么跟 `deploy/server/` 下的同名脚本不一样？

| `bin/start.sh`（本地） | `deploy/server/start.sh`（服务器） |
|---|---|
| Mac 上跑，`docker run -d ...` 拼装长参数 | Linux 服务器上跑，`docker compose up -d` |
| 镜像来源：本机 docker images | 镜像来源：服务器本地（已 `docker load`） |
| 前端是独立容器 `nginx:stable-alpine` | 前端是 compose 服务 `panjia-console-web` |
| 网络：`--link` 老方案 | compose 网络 |

**两者同名只是历史遗留，没有任何复用代码**。

## 不会传到什么

- ❌ 不会进 docker 镜像（`Dockerfile` 不 COPY bin/）
- ❌ 不会 scp 到服务器（`setup-server.sh` 只 cp `deploy/server/*` 到 `/opt/panjia-console/`）
- ❌ 不会进 git tag（这些脚本的开发环境相关性太强）