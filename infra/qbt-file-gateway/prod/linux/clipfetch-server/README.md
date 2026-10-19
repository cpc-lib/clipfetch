# ClipFetch Server — Linux Spring Boot 下载代理部署手册

本目录用于 **ClipFetch Spring Boot 所在的 Linux 服务器**，与 qBittorrent 主机的文件网关分开部署。只需要本目录的 Nginx 片段和 Spring Boot 项目，不需要 Python 网关、qBittorrent 文件目录、systemd 的 `clipfetch-file-gateway.service`，也不使用 Docker Compose 或 SMB/SSHJ/SFTP。

**传统 Nginx 的安装路径已按实际环境调整为 `/usr/local/nginx/conf`。**

> 当前通信使用 HTTP 明文，HMAC 校验不能加密下载内容或文件路径。Spring Boot 只能连接经过授权的 qBittorrent 网关，qBittorrent 端必须通过 Nginx 和防火墙将 `18767/TCP` 限定到 Spring Boot 出口 IP。不要公开 `QBT_FILE_GATEWAY_SECRET`。

## 1. 本目录文件

```text
clipfetch-server/
├── README.md
└── nginx.springboot-download.conf    # 浏览器下载专用 location{}
```

**本目录不包含，也不需要：**

- `gateway.py`：应运行在 **qBittorrent Linux 主机**，见 `../qbittorrent-server/README.md`。
- `clipfetch-file-gateway.service`：只在 qBittorrent 主机安装，**不应在 Spring Boot 主机重复安装**。
- Docker Compose / Docker Nginx：当前按传统安装的 Nginx 部署。

工作流程：

```text
用户浏览器
  │ 请求 ClipFetch 网站 /api/magnet/downloads/stream/{ticket}
  ▼
Spring Boot 主机 Nginx（你的现有站点，例如 :80）
  │ 将 HTTP Range 原样转发，不缓存大文件
  ▼
Spring Boot :8082（校验票据、用户/文件权限）
  │ HttpClient + HMAC-SHA256 + Range，流式读取
  ▼
另一台 qBittorrent Linux 主机 Nginx :18767
  │ http://127.0.0.1:18766
  ▼
Python 网关：从 qBittorrent 宿主机磁盘读取文件
```

浏览器**不直接访问 qBittorrent 文件网关**。先在 ClipFetch 登录，再由后端签发短期文件票据；文件数据从远程网关流式转发，不需要在 Spring Boot 本机保存整份文件，也不使用前端 Axios Blob 加载数 GB 文件。

## 2. 部署前确认三个地址

先明确：

| 配置 | 填写什么 | 示例 |
| --- | --- | --- |
| qBittorrent Web API 地址 | Spring Boot 原有的 qBittorrent API 入口 | 你原来 `qbt-host` 的实际地址 |
| `QBT_FILE_GATEWAY_URL` | qBittorrent 主机的 Nginx HTTP 文件网关 | `http://<qbt主机IP>:18767` |
| Spring Boot 本机 HTTP 端口 | Nginx `proxy_pass` 应连接的地址 | `http://127.0.0.1:8082` |

**qBittorrent Web API 和文件网关不是同一个服务**，不要把 `QBT_FILE_GATEWAY_URL` 指向 qBittorrent Web UI 端口或 Python 内部的 `18766`。

还需确定 Spring Boot 服务器对外访问 qBittorrent 时的**实际出口 IP**，让 qBittorrent 服务器管理员在 `nginx.gateway.conf` 的 `allow` 规则和主机/云防火墙中放行。使用 NAT/代理/VPN 时，出口 IP 可能不是本机网卡 IP。

## 3. 配置 Spring Boot backend/.env

在 **Spring Boot 项目的 `backend/.env`** 中写入或修改：

```dotenv
# 必须等于 qBittorrent Web API 报告的下载根目录；Docker 下通常是容器内路径
QBT_SAVEPATH=/downloads

# qBittorrent Linux 主机 Nginx 对 Spring Boot 开放的 HTTP 地址
QBT_FILE_GATEWAY_URL=http://<实际qBittorrent服务器可达IP>:18767

# 与 qBittorrent 主机 /etc/clipfetch-file-gateway.conf 的 SECRET 完全一致
QBT_FILE_GATEWAY_SECRET=<相同的随机共享密钥>

# 当前使用 HTTP，必须显式启用（将来改 HTTPS 后可关闭）
QBT_FILE_GATEWAY_ALLOW_HTTP=true
```

保留原有 `qbt-host`、`qbt-user`、`qbt-pass` 等 qBittorrent API 配置，不要与文件网关密钥混淆。

### 路径映射举例

如果 qBittorrent Docker 向 API 报告文件保存路径：

```text
/downloads/85dad6bc-.../example.iso
```

而 qBittorrent Linux **宿主机**真实目录是：

```text
/srv/qbittorrent/downloads/85dad6bc-.../example.iso
```

那么：

- Spring Boot `QBT_SAVEPATH=/downloads`；
- qBittorrent 主机 Python `QBT_FILE_ROOT=/srv/qbittorrent/downloads`；
- 网关只收到根目录下面的相对路径，例如 `85dad6bc-.../example.iso`。

**Spring Boot 不需要挂载 qBittorrent 主机的目录**，也不需要配置旧的 `QBT_DOWNLOADS_HOST_DIR`。

### 修改环境变量后必须重启 Spring Boot

当前应用在启动时读取 `.env`，因此只修改文件而不重启不会立即生效。按你实际的启动方式重启 **Spring Boot 应用**，不必因为变更 `.env` 而重启 qBittorrent 或 Python 文件网关。

## 4. 在 /usr/local/nginx 安装下载 location 配置

### 4.1 确认现有 Nginx 安装路径

```bash
ls -l /usr/local/nginx/sbin/nginx
sudo /usr/local/nginx/sbin/nginx -t -c /usr/local/nginx/conf/nginx.conf
sudo /usr/local/nginx/sbin/nginx -T -c /usr/local/nginx/conf/nginx.conf
```

`nginx.springboot-download.conf` **只有 `location {}` 片段，没有 `server {}`**，所以不能直接放进 `conf.d/*.conf` 由 `http {}` 自动加载，否则可能报 `location directive is not allowed here`。

### 4.2 安装到 snippets 目录

在本 `clipfetch-server/` 目录执行：

```bash
sudo mkdir -p /usr/local/nginx/conf/snippets
sudo install -m 644 nginx.springboot-download.conf \
  /usr/local/nginx/conf/snippets/clipfetch-download.conf
```

### 4.3 修改当前 ClipFetch 网站的 server{}

用 `sudo /usr/local/nginx/sbin/nginx -T -c /usr/local/nginx/conf/nginx.conf` 找到**浏览器实际访问的 ClipFetch 网站**对应的 `server {}`。这个块可能直接位于 `/usr/local/nginx/conf/nginx.conf`，也可能在该文件 include 的其他站点配置里。

**只在该现有网站 `server {}` 内新增：**

```nginx
include /usr/local/nginx/conf/snippets/clipfetch-download.conf;
```

示例（说明放置位置，**不能覆盖你现有的完整站点配置**）：

```nginx
server {
    listen 80;
    server_name example.internal;

    # 新增：原生浏览器大文件下载
    include /usr/local/nginx/conf/snippets/clipfetch-download.conf;

    # 现有通用后端接口，保留原有配置
    location /api/ {
        proxy_pass http://127.0.0.1:8082;
    }

    # 其他前端、WebSocket、SSL 等 location 原样保留
}
```

片段中已经定义：

```nginx
location ^~ /api/magnet/downloads/stream/ {
    proxy_pass http://127.0.0.1:8082;
    proxy_http_version 1.1;
    proxy_set_header Range $http_range;
    proxy_buffering off;
    proxy_read_timeout 3600s;
    proxy_send_timeout 3600s;
    access_log off;
}
```

因此它优先匹配浏览器原生下载路径，避免通用 API 代理的缓冲或短超时。真实文件还包含其他必要的代理头。

**若 Spring Boot 不是运行在当前 Linux 宿主机的 8082 端口**，要修改 `nginx.springboot-download.conf` 的 `proxy_pass` 为实际可达地址。**不要**直接把 `127.0.0.1` 当作另一台服务器的地址。

### 4.4 验证并平滑加载传统 Nginx

```bash
sudo /usr/local/nginx/sbin/nginx -t -c /usr/local/nginx/conf/nginx.conf
# 只有 test is successful 才 reload
sudo /usr/local/nginx/sbin/nginx -s reload -c /usr/local/nginx/conf/nginx.conf

sudo /usr/local/nginx/sbin/nginx -T -c /usr/local/nginx/conf/nginx.conf \
  | grep -F 'clipfetch-download.conf'
```

`nginx -t` 出错时先恢复/修复现有网站配置，**不要反复重启整个 Nginx**。如已安装另一套系统 Nginx，注意不要混用可执行文件和主配置。

## 5. 验证跨服务器连接

### 5.1 检查到远程文件网关的网络

在 **Spring Boot Linux 主机**：

```bash
# 替换成真实 qBittorrent 服务器 IP
curl -sS -o /dev/null -w '%{http_code}\n' \
  'http://<qBittorrent服务器IP>:18767/internal/files?path=test'
```

在网关已安装且 Spring Boot 的 IP 已加入白名单时，由于没有 HMAC 签名，预期 **HTTP 403**。

不同结果的意义：

| 返回 | 判断 |
| --- | --- |
| `403` | 至少说明已到达拒绝未授权请求的服务；不代表合法签名传输已经成功 |
| `502` | 远程 Nginx 无法连接 Python 网关，联系 qBittorrent 服务器检查 :18766 |
| `Connection refused` | 18767 没有监听，或被防火墙阻断/拒绝 |
| `Timeout` | 防火墙、云安全组、路由、网络访问控制问题 |
| `404` | 检查是否访问了正确的 Nginx server / 路径 |

**不要为了得到 200 而取消文件网关的 HMAC 验证。**

### 5.2 检查 Spring Boot 接口

Spring Boot 需要处于运行状态并且确实加载了 `.env` 中的配置。用登录后的 ClipFetch 页面，选中一个 qBittorrent 已经完整下载的文件，在下载列表点击文件下载。

正常顺序：

1. 前端携带 JWT 调用 `POST /api/magnet/downloads/{hash}/file-ticket` 领取短期下载凭证。
2. 后端核对用户、任务归属、所选文件和已完成状态。
3. 浏览器请求 `GET /api/magnet/downloads/stream/{ticket}`，Nginx 代理到 Spring Boot。
4. Spring Boot 通过签名 HTTP 请求访问 qBittorrent 主机文件网关。
5. 网关从 qBittorrent 磁盘读取、流式转发；浏览器接收下载，可通过 HTTP 单段 `Range` 请求 `206 Partial Content`。

文件网关请求不允许用户自行拼接任意绝对路径。网页下载 URL 中的临时票据属于敏感访问凭证；配置的下载 `location` 已关闭 access log，请确保其他外层代理/CDN 也不会将票据 URL 记录到公开日志。

## 6. 故障排查

| 现象 | 检查方向 |
| --- | --- |
| “未配置 QBT_FILE_GATEWAY_URL / SECRET” | 检查 `backend/.env`，然后重启 Spring Boot |
| “文件网关必须使用 HTTPS” | 目前走 HTTP，核对 `QBT_FILE_GATEWAY_ALLOW_HTTP=true` |
| Spring Boot 报 502 | 检查 qBittorrent 服务器 Nginx 的 18767、Python 的 18766 |
| HTTP 403 | 远程 Nginx IP 白名单、HMAC 密钥或两端系统时间不同步 |
| HTTP 404 | qBittorrent 实际文件目录、网关 `QBT_FILE_ROOT` 和 `QBT_SAVEPATH` 的相对路径是否一致 |
| 浏览器大文件下载中断 | Spring Boot/Nginx 超时，或客户端连接中断；使用支持 Range 的下载方式重试 |
| `location directive is not allowed here` | 应在网站的 `server {}` 内 include，不能在 `http {}` 顶层直接加载 |
| `nginx -t` 报重复 location | 当前站点已经定义了同一路径，须合并配置而不是增加第二个相同 location |

**这里只说明 Spring Boot 机器的部署；qBittorrent 服务器的 Python、systemd 和 Nginx 配置全部位于 `../qbittorrent-server/README.md`。**
