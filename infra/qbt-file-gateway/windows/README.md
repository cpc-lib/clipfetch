# ClipFetch 文件网关 — Windows 部署指南（HTTP）

本文只包含 **qBittorrent 运行在 Windows 电脑上**的网关配置和启动方法。将整个 `windows/` 目录复制到 Windows 主机即可使用，**不需要 Nginx、systemd、SMB、SSHJ、SFTP 或共享盘**。

> 当前版本使用 **HTTP 明文**，HMAC 签名只验证请求真实性，**不会加密文件内容**。如果 Spring Boot 与 Windows 主机通过公网连接，强烈建议仅放行 Spring Boot 服务器的出口 IP；不要把文件网关端口开放给整个公网。后续可以通过加密隧道或 HTTPS 增加传输保密性。

## 1. Windows 目录文件

| 文件 | 功能 |
|---|---|
| `gateway.py` | Python 3.9+ 只读文件服务，HMAC 请求验证、HTTP Range |
| `gateway_check.py` | 网关单元测试，包含超 2GB 文件范围读取 |
| `gateway-windows.conf` | Windows 路径、监听地址、白名单、共享密钥 |
| `start-gateway-windows.ps1` | 读取配置并启动 Python |
| `start-gateway-windows.cmd` | 双击启动入口 |

数据流：

```text
用户浏览器 -> ClipFetch Spring Boot 服务器
               -> HTTP + HMAC-SHA256 -> Windows 电脑 :18766
                                        -> gateway.py
                                           -> qBittorrent 文件目录
```

浏览器不会直接访问 Windows 文件网关；它访问 ClipFetch 的文件下载 URL，Spring Boot 校验用户及文件后再分块代理至 Windows。

## 2. 安装 Python 并填写 Windows 配置

安装 Python **3.9 或更新版本**，在 PowerShell 验证：

```powershell
python --version
```

编辑本目录的 `gateway-windows.conf`。示例：

```dotenv
QBT_FILE_ROOT=D:/qBittorrent/downloads
QBT_FILE_GATEWAY_BIND=127.0.0.1
QBT_FILE_GATEWAY_PORT=18766
QBT_FILE_GATEWAY_ALLOW_REMOTE=false
QBT_FILE_GATEWAY_ALLOWED_IPS=127.0.0.1
QBT_FILE_GATEWAY_SECRET=<至少32字符的随机密钥>
```

- `QBT_FILE_ROOT` 必须是 Windows **实际的 qBittorrent 保存根目录**，不是网页虚构的路径。目录必须真实存在，服务账户有读取权限。
- `QBT_FILE_GATEWAY_SECRET` 应随机生成并与 Spring Boot 的设置一致。可以在 PowerShell 中生成：

```powershell
$bytes = New-Object byte[] 32
$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
[BitConverter]::ToString($bytes).Replace('-', '').ToLowerInvariant()
```

生成后复制到 `gateway-windows.conf` 和 Spring Boot 服务器的 `.env`。不要提交真实密钥到 Git，也不要输出到网页。

## 3. 启动网关

只进行本机测试时保留 `QBT_FILE_GATEWAY_BIND=127.0.0.1`。双击：

```text
start-gateway-windows.cmd
```

或在 PowerShell 中运行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\start-gateway-windows.ps1
```

脚本会检查下载根目录、共享密钥长度并以当前进程环境变量启动 `gateway.py`。按 `Ctrl+C` 结束服务。它不会修改 qBittorrent 的下载任务或文件。

## 4. Spring Boot 在另外一台服务器（跨网络访问）

需要编辑 Windows `gateway-windows.conf`，**显式开启远程监听和来源白名单**：

```dotenv
QBT_FILE_ROOT=D:/qBittorrent/downloads
QBT_FILE_GATEWAY_BIND=0.0.0.0
QBT_FILE_GATEWAY_PORT=18766
QBT_FILE_GATEWAY_ALLOW_REMOTE=true
QBT_FILE_GATEWAY_ALLOWED_IPS=<Spring Boot服务器的出口公网IP>
QBT_FILE_GATEWAY_SECRET=<同一个随机密钥>
```

Windows 防火墙也需要允许**来自指定 Spring Boot 出口 IP**的 TCP 18766 入站流量；如果主机在路由器后面，还需要网络端口转发或可达的私有网络连接。请不要为了测试关闭 Windows 防火墙，不要对所有来源放行 18766。

下面是 Windows Defender 防火墙的**限定远程 IP**规则示例，先把 `<SpringBoot出口IP>` 替换为真实 IP：

```powershell
New-NetFirewallRule -DisplayName "ClipFetch Gateway" -Direction Inbound -Action Allow -Protocol TCP -LocalPort 18766 -RemoteAddress <SpringBoot出口IP>
```

该命令通常需要在管理员 PowerShell 中执行。如果已存在更宽泛的端口允许规则，要先核对它们，单条限制来源规则不能覆盖更宽泛的允许规则。

## 5. Spring Boot 服务器环境变量

在 Spring Boot 的 `backend/.env` 中配置（参数值均为示例）：

```dotenv
QBT_SAVEPATH=D:/qBittorrent/downloads
QBT_FILE_GATEWAY_URL=http://<Windows主机可访问的IP或域名>:18766
QBT_FILE_GATEWAY_SECRET=<与Windows网关一致的随机密钥>
QBT_FILE_GATEWAY_ALLOW_HTTP=true
```

`QBT_SAVEPATH` 需要和 qBittorrent Web API 返回的保存根目录一致。文件服务内部将 Windows 路径转换为根目录下的相对文件路径；不要将 `QBT_FILE_ROOT` 配成与 qBittorrent 实际文件位置不一致的目录。

Spring Boot 接收网页文件请求后再流式读取 Windows 网关的数据，不再需要旧的 `QBT_DOWNLOADS_HOST_DIR` 映射，也不会先把整个文件写到 Spring Boot 磁盘。修改 `.env` 后重启后端。

## 6. Windows 测试与排错

进入本 `windows/` 目录运行：

```powershell
python -m unittest -v gateway_check
```

测试包括：HMAC 校验、签名过期、IP 来源限制、路径穿越拦截、HTTP 206/416、超 2 GiB 文件的 Range。测试不读取或修改真实 qBittorrent 下载数据。

- **启动失败提示找不到目录**：检查 `gateway-windows.conf` 的 `QBT_FILE_ROOT` 是否真实存在且可读。
- **403**：检查共享密钥、Spring Boot 与 Windows 的时间同步、`QBT_FILE_GATEWAY_ALLOWED_IPS`，以及 Windows 防火墙。
- **404**：检查 qBittorrent 的实际文件路径和任务保存目录、文件是否被移动或删除。
- **502 或连接拒绝**：检查 `gateway.py` 是否运行、监听的是 127.0.0.1 还是 0.0.0.0、Windows 网络是否可达。
- **大文件下载中断**：后端和文件网关支持 HTTP Range 单区间续传，但不同浏览器的自动恢复行为不完全相同。

Windows 版本不需要安装 Nginx，也不需要部署 Linux 的 `docker-compose.gateway.yml` 或 `clipfetch-file-gateway.service`。
