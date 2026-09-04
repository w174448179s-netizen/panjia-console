# bin/ —— 本地开发与运维脚本

> **运行位置：工程师的 Mac 本地**。这些脚本**永远不**会传到服务器，也**不**会进 docker 镜像。

## 目录职责

| 脚本 | 用途 | 运行时机 |
|---|---|---|
| `build.sh` | 本地构建后端 Spring Boot jar + 前端 dist | 改完代码、准备部署前 |
| `gen_keypair.sh` | 生成 JKS 密钥对（首次部署、轮换） | 项目初始化、密钥到期前 |
| `setup-server.sh` | 远程初始化服务器（装 docker、申请证书、部署） | 新服务器首次部署、域名/HTTPS 切换 |
| `add-domain.sh` | 给已部署的 IP 模式服务器加域名（stage 2） | 域名解析到位后 |
| `push-frontend.sh` | **只**推送前端（Vue 改动） | 改完 Vue 代码、跳过后端发布 |
| `push-backend.sh` | **只**推送后端（Java/Dockerfile/compose 改动） | 改完后端代码、跳过前端发布 |
| `publish.sh` | **一键发布**：backend + frontend + 健康检查 | 改完代码、想一次推到服务器并自动验证 |
| `deploy-to-server.sh` | 老的全量发布脚本，已被 setup-server.sh + push-* 取代 | 留作参考，不再使用 |
| `start.sh` / `stop.sh` / `restart.sh` / `logs.sh` | **本地 docker run 控制**（拼装长参数） | 本地起开发环境 |

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