# qBittorrent Server — Linux 文件网关部署手册

本目录可独立复制到 **qBittorrent 所在 Linux 服务器** 部署，不依赖 `clipfetch-server` 子目录里的文件。部署方式：**Python 3.9+ + systemd + 传统 Nginx**，Nginx 安装路径为 `/usr/local/nginx`，不使用 Docker Compose、Nginx Docker、SMB、SFTP、SSHJ 或共享磁盘。

> **HTTP 明文风险：** 当前服务间传输是 HTTP + HMAC-SHA256。HMAC 能验证请求，却不加密文件内容和文件路径。在公网传输时必须限制 TCP 18767 仅允许 ClipFetch Spring Boot 服务器的真实出口 IP，同时配置主机/云防火墙。需要机密性时，应升级 TLS 或加密网络通道。

## 1. 本目录文件

```text
qbittorrent-server/
├── README.md
├── gateway.py                       # 只读 HTTP 文件网关，支持 Range
├── gateway_check.py                 # 网关自带测试
├── gateway-linux.conf               # 网关运行参数示例
├── clipfetch-file-gateway.service   # systemd 服务单元
└── nginx.gateway.conf               # 独立 Nginx server{} 配置
```

服务链路：

```text
ClipFetch Spring Boot（另一个服务器）
    │ HTTP + HMAC-SHA256，请求 /internal/files?path=...
    ▼
qBittorrent 主机传统 Nginx :18767
    │ 仅允许 Spring Boot 出口 IP，反向代理
    ▼
Python 网关 127.0.0.1:18766
    │ 只读、校验路径、读取指定字节
    ▼
qBittorrent 文件实际所在的 Linux 宿主机目录
```

| 参数 | 用途 |
| --- | --- |
| `18767/TCP` | Nginx 对 Spring Boot 开放的 HTTP 入口，只允许指定出口 IP |
| `127.0.0.1:18766` | Python 网关内部端口，**不对公网开放** |
| `QBT_FILE_ROOT` | Python 所在 Linux **宿主机的实际下载目录** |
| `QBT_FILE_GATEWAY_SECRET` | 两端一致的随机 HMAC 共享密钥，至少 32 字符 |
| `/usr/local/nginx/conf/nginx.conf` | 现有传统 Nginx 主配置 |

## 2. 检查运行环境与真实文件目录

在 **qBittorrent Linux 主机** 运行：

```bash
python3 --version
command -v python3
ls -l /usr/local/nginx/sbin/nginx
sudo /usr/local/nginx/sbin/nginx -t -c /usr/local/nginx/conf/nginx.conf
```

Python 要求 3.9+。如果 `command -v python3` 不是 `/usr/bin/python3`，必须修改本目录 `clipfetch-file-gateway.service` 的 `ExecStart`。

确认 **实际宿主机** 下载目录。例如 qBittorrent Docker 的挂载如果是：

```text
宿主机 /srv/qbittorrent/downloads  ->  容器 /downloads
```

则本机 Python 网关使用：

```dotenv
QBT_FILE_ROOT=/srv/qbittorrent/downloads
```

而 **Spring Boot** 的 `QBT_SAVEPATH=/downloads` 是 qBittorrent API 返回的容器可见路径，两者并不是相同概念。可用下面的只读命令核对 qBittorrent 容器挂载：

```bash
docker inspect <实际qBittorrent容器名> --format '{{json .Mounts}}'
```

没有使用 Docker 的话，直接把 `QBT_FILE_ROOT` 配成 qBittorrent 本机实际保存根目录。不要凭目录名称猜测。

## 3. 安装 Python 网关

先上传本 `qbittorrent-server/` 目录到 Linux 任意临时工作目录，进入该目录后执行：

```bash
# 创建专用低权限账号，已存在时不重复创建
id clipfetch-gateway >/dev/null 2>&1 || \
  sudo useradd -r -M -s "$(command -v nologin)" clipfetch-gateway

# 安装只读网关代码
sudo install -d -o root -g root -m 755 /opt/clipfetch-file-gateway
sudo install -o root -g root -m 644 gateway.py /opt/clipfetch-file-gateway/gateway.py
sudo install -o root -g root -m 644 gateway_check.py /opt/clipfetch-file-gateway/gateway_check.py
```

### 3.1 安装环境变量文件

```bash
sudo install -o root -g root -m 600 gateway-linux.conf /etc/clipfetch-file-gateway.conf
openssl rand -hex 32
sudoedit /etc/clipfetch-file-gateway.conf
```

把生成的密钥填入配置文件，同时按实际修改 `QBT_FILE_ROOT`：

```dotenv
QBT_FILE_ROOT=/srv/qbittorrent/downloads
QBT_FILE_GATEWAY_BIND=127.0.0.1
QBT_FILE_GATEWAY_PORT=18766
QBT_FILE_GATEWAY_ALLOW_REMOTE=false
QBT_FILE_GATEWAY_ALLOWED_IPS=127.0.0.1
QBT_FILE_GATEWAY_SECRET=<实际生成的64位十六进制随机密钥>
```

不要使用示例占位值 `CHANGE_ME_BEFORE_STARTING`。**在 ClipFetch Spring Boot 端填写同一个密钥**，不要将密钥写入 README、Git 或前端环境变量。

校验配置文件权限：

```bash
sudo stat -c '%U:%G %a %n' /etc/clipfetch-file-gateway.conf
# 预期：root:root 600 /etc/clipfetch-file-gateway.conf
```

### 3.2 检查读取权限

使用实际已完成文件验证专用用户是否具有读取权限：

```bash
namei -l /srv/qbittorrent/downloads/<任务目录>/<已完成文件>
sudo -u clipfetch-gateway test -r /srv/qbittorrent/downloads/<任务目录>/<已完成文件>
echo $?
```

退出码 `0` 表示基础读取权限正常。目录逐级需要遍历权限，文件需要读取权限。请用 ACL/专用组按需配置，**不要使用 `chmod -R 777`**。如果下载目录位于 `/home`，systemd 的 `ProtectHome=true` 可能额外阻止访问，应考虑将下载目录放在专用挂载位置或针对性调整服务限制。

## 4. 安装 clipfetch-file-gateway.service（systemd）

### 4.1 复制到 systemd 单元目录

```bash
sudo install -o root -g root -m 644 clipfetch-file-gateway.service \
  /etc/systemd/system/clipfetch-file-gateway.service

sudo cat /etc/systemd/system/clipfetch-file-gateway.service
```

主要字段已在仓库中的 `clipfetch-file-gateway.service` 配置好：

| 字段 | 说明 |
| --- | --- |
| `User=clipfetch-gateway` | 网关以普通受限账号运行 |
| `WorkingDirectory=/opt/clipfetch-file-gateway` | Python 网关文件目录 |
| `EnvironmentFile=/etc/clipfetch-file-gateway.conf` | 读取本机文件根目录、监听配置与密钥 |
| `ExecStart=/usr/bin/python3 -u /opt/clipfetch-file-gateway/gateway.py` | 启动网关；Python 路径需与机器实际安装一致 |
| `Restart=on-failure` | 异常退出后自动重启 |
| `NoNewPrivileges=true`、`ProtectSystem=strict` | 限制权限与系统写入 |
| `StandardOutput=journal` | 运行日志交给 journalctl |

如果 Python 不在 `/usr/bin/python3`，先修改安装后的 service：

```bash
sudo vi /etc/systemd/system/clipfetch-file-gateway.service
# 只替换 ExecStart= 中的 Python 绝对路径
```

### 4.2 验证、开机自启并启动

```bash
sudo systemd-analyze verify /etc/systemd/system/clipfetch-file-gateway.service
sudo systemctl daemon-reload
sudo systemctl enable --now clipfetch-file-gateway.service
sudo systemctl is-enabled clipfetch-file-gateway.service
sudo systemctl is-active clipfetch-file-gateway.service
sudo systemctl status clipfetch-file-gateway.service --no-pager -l
```

正常应看到 `enabled`、`active`，并监听本机端口：

```bash
sudo ss -lntp | grep ':18766'
# 预期监听 127.0.0.1:18766
```

### 4.3 日常管理

```bash
# 代码或网关环境变量修改后
sudo systemctl restart clipfetch-file-gateway.service

# service 文件修改后，必须先 daemon-reload
sudo systemctl daemon-reload
sudo systemctl restart clipfetch-file-gateway.service

# 查看最近 100 条日志
sudo journalctl -u clipfetch-file-gateway.service -n 100 --no-pager

# 实时日志
sudo journalctl -u clipfetch-file-gateway.service -f

# 手工停用（只停止文件网关，不停止 qBittorrent）
sudo systemctl stop clipfetch-file-gateway.service

# 不再开机自启
sudo systemctl disable clipfetch-file-gateway.service
```

本机无签名健康探测：

```bash
curl -sS -o /dev/null -w '%{http_code}\n' \
  'http://127.0.0.1:18766/internal/files?path=test'
```

预期 HTTP `403`（没有 HMAC 签名就应拒绝）。`Connection refused` 则应先检查 systemd 和端口。

## 5. 安装传统 Nginx（/usr/local/nginx/conf）

### 5.1 复制 Nginx 站点文件

在 **qBittorrent 主机** 的本目录执行：

```bash
sudo mkdir -p /usr/local/nginx/conf/conf.d
sudo install -m 644 nginx.gateway.conf \
  /usr/local/nginx/conf/conf.d/clipfetch-file-gateway.conf
```

然后检查主配置 `/usr/local/nginx/conf/nginx.conf` 的 **`http { ... }` 内**有没有：

```nginx
include /usr/local/nginx/conf/conf.d/*.conf;
```

若没有，使用 `sudo vi /usr/local/nginx/conf/nginx.conf` **只添加这行 include**，不要覆盖现有 Nginx 的配置或其他站点。

### 5.2 设置来源 IP 白名单

站点模板默认只有：

```nginx
allow 127.0.0.1;
deny all;
```

因此默认 **拒绝公网访问**。需要在 `deny all;` 之前增加 **Spring Boot 服务器实际的出口 IP**：

```bash
sudo vi /usr/local/nginx/conf/conf.d/clipfetch-file-gateway.conf
```

例如（示例地址绝不能照抄）：

```nginx
allow 203.0.113.25;  # 替换为真实 Spring Boot 出口 IP
allow 127.0.0.1;
deny all;
```

同时在 **Linux 系统防火墙及云安全组** 将 TCP 18767 入站来源限定为该 IP，不能向所有公网地址开放；不要开放 Python 内部端口 18766。

### 5.3 检查、重载及监听

```bash
sudo /usr/local/nginx/sbin/nginx -t -c /usr/local/nginx/conf/nginx.conf
# 确认 test is successful 后再 reload
sudo /usr/local/nginx/sbin/nginx -s reload -c /usr/local/nginx/conf/nginx.conf

sudo ss -lntp | grep -E ':18766|:18767'
sudo /usr/local/nginx/sbin/nginx -T -c /usr/local/nginx/conf/nginx.conf \
  | grep -F 'clipfetch-file-gateway.conf'
```

如果当前 Nginx 使用了其他二进制安装路径，请统一替换 `/usr/local/nginx/sbin/nginx`。由于你的 Nginx 是独立安装，**不要默认 `systemctl reload nginx` 对应的就是这一套程序**。

无签名访问 Nginx 入口：

```bash
curl -sS -o /dev/null -w '%{http_code}\n' \
  'http://127.0.0.1:18767/internal/files?path=test'
```

正常 HTTP `403`，如果 `502`，检查 Python 端口是否启动，及 `nginx.gateway.conf` 中的 `proxy_pass http://127.0.0.1:18766;`。

## 6. 验收和故障排查

执行随包测试：

```bash
cd /opt/clipfetch-file-gateway
python3 -m unittest -v gateway_check
```

| 表现 | 优先检查 |
| --- | --- |
| systemd `203/EXEC` | Python 执行文件路径、解释器是否存在 |
| systemd `200/CHDIR` | `/opt/clipfetch-file-gateway` 是否存在 |
| `QBT_FILE_ROOT is missing` | 环境变量路径与实际 Linux 目录是否一致 |
| `Permission denied` | 专用用户权限，以及 `ProtectHome` 的限制 |
| HTTP 403 | Nginx IP 白名单、HMAC 密钥、服务器时钟与网关 ALLOWED_IPS |
| HTTP 404 | 物理文件不存在、错误的 Docker volume 映射 |
| HTTP 502 | Python 网关未运行或 Nginx 后端端口错误 |
| 断流或续传失败 | Nginx 超时与缓冲、Spring Boot Range 转发、网络质量 |

**Spring Boot 端的全部配置与 Nginx 说明请看另一个独立文件：`../clipfetch-server/README.md`。** 本目录只包含 qBittorrent 机器必须部署的文件。
