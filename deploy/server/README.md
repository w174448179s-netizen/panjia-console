# 服务器部署指南

> **本目录的所有文件都会被 scp 上传到 Linux 服务器**（`/opt/panjia-console/`），由服务器上的 docker compose 使用。
>
> 与 `bin/` 的同名脚本（`start.sh` / `stop.sh` / `restart.sh` / `logs.sh`）**没有任何代码关系**：
>
> | 目录 | 运行位置 | 启动方式 |
> |---|---|---|
> | `bin/` | Mac 本地 | `docker run -d ...` 拼长参数 |
> | `deploy/server/` | Linux 服务器 | `docker compose up -d` |

## 一、首次部署完整流程

### 1. 服务器初始化（在服务器上执行）

```bash
# 上传初始化脚本到服务器
scp deploy/server/server-init.sh root@你的服务器IP:/root/

# SSH 登录服务器
ssh root@你的服务器IP

# 执行初始化（需要 root 权限）
chmod +x server-init.sh
./server-init.sh
```

脚本会自动完成：
- 安装 Docker + docker compose
- 创建部署目录 `/opt/panjia-console/`
- 设置目录权限
- 配置 Docker 开机自启
- 开放防火墙 8080 端口

### 2. 准备配置文件

在本地修改好配置后上传：

```bash
# 1. 生成生产环境 JKS 密钥（密码自己定，记下来）
sh bin/gen_keypair.sh 你的强密码

# 2. 上传 JKS 到服务器
scp script/keys/panjia-license.jks root@你的服务器IP:/opt/panjia-console/keys/

# 3. 上传部署文件
scp deploy/server/docker-compose.yml root@你的服务器IP:/opt/panjia-console/
scp deploy/server/.env.example root@你的服务器IP:/opt/panjia-console/.env
scp deploy/server/start.sh deploy/server/stop.sh deploy/server/restart.sh deploy/server/logs.sh root@你的服务器IP:/opt/panjia-console/

# 4. SSH 登录服务器，编辑 .env 修改所有密码
ssh root@你的服务器IP
cd /opt/panjia-console
vi .env   # 修改 POSTGRES_PASSWORD、PANJIA_JKS_PASSWORD 等
chmod 600 .env
chmod +x *.sh
```

### 3. 首次发布（在本地执行）

```bash
sh bin/deploy-to-server.sh 你的服务器IP
```

脚本会自动完成：
1. 本地构建 Docker 镜像
2. 导出镜像为 tar 文件
3. SCP 上传到服务器
4. 服务器端导入镜像并启动

---

## 二、后续版本更新

```bash
# 一行命令搞定
sh bin/deploy-to-server.sh 你的服务器IP v1.1.0
```

---

## 三、服务器上的运维操作

登录服务器后在 `/opt/panjia-console/` 目录下执行：

| 命令 | 作用 |
|------|------|
| `./start.sh` | 启动服务 |
| `./stop.sh` | 停止服务 |
| `./restart.sh` | 重启服务 |
| `./logs.sh` | 实时查看日志 |
| `./logs.sh --tail 200` | 查看最近 200 行日志 |
| `docker compose ps` | 查看服务状态 |

---

## 四、目录结构（服务器端）

```
/opt/panjia-console/
├── docker-compose.yml    # 容器编排配置
├── .env                  # 环境变量（密码等敏感信息，权限 600）
├── start.sh              # 启动脚本
├── stop.sh               # 停止脚本
├── restart.sh            # 重启脚本
├── logs.sh               # 日志脚本
├── keys/                 # JKS 密钥（权限 700）
│   └── panjia-license.jks
├── backups/              # 数据库备份文件（权限 700）
├── logs/                 # 应用日志
└── data/
    └── postgres/         # PostgreSQL 数据
```

---

## 五、数据备份

### 数据库备份

应用内自带备份功能（后台 → 系统管理 → 备份管理），备份文件会生成在 `/opt/panjia-console/backups/`。

也可以手动备份：

```bash
# 手动备份 PostgreSQL
docker exec panjia-postgres pg_dump -U panjia -F c -f /tmp/backup_$(date +%Y%m%d).dump panjia
docker cp panjia-postgres:/tmp/backup_$(date +%Y%m%d).dump ./backups/
```

### 密钥备份

**务必单独备份 JKS 文件和密码，与数据库备份分开存放：**

```bash
# 备份 JKS（下载到本地安全位置）
scp root@服务器IP:/opt/panjia-console/keys/panjia-license.jks ./backup-keys/
```

> JKS 文件丢失 = 所有客户端授权作废，必须重新激活。

---

## 六、常见问题

### 服务启动失败？

```bash
# 看日志排查
./logs.sh
```

### 端口访问不了？

1. 检查防火墙：`ufw status` 或 `firewall-cmd --list-ports`
2. 检查云服务器安全组（阿里云/腾讯云后台）
3. 确认服务在运行：`docker compose ps`

### 想换端口？

编辑 `.env` 里的 `HOST_PORT`，然后 `./restart.sh`。

### 想升级 PostgreSQL 配置？

编辑 `.env` 里的 `JAVA_OPTS` 调整内存，然后 `./restart.sh`。
