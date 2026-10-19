# ClipFetch 文件网关 — Linux 测试版（允许任意来源 IP）

基于 `../prod/windows/` 直跑版改写：Python 直接对外监听，**不需要 Nginx、systemd、Docker、SMB、SSHJ、SFTP 或共享盘**。与正式版 `../prod/linux/` 的唯一区别：本套放开了来源 IP 白名单（`0.0.0.0/0, ::/0`），任意 IP 都能连接网关端口；HMAC-SHA256 签名验证仍然生效，**密钥是唯一的访问凭证**。

> 仅用于测试。HTTP 明文 + 任意来源 IP：任何拿到密钥的人都能读取下载目录里的文件，路径也不加密。不要用于正式环境；`local/` 目录**不在** `.gitignore` 中，注意不要把含真实密钥的 `gateway-linux.conf` 提交到 Git。

## 1. 本目录文件

| 文件 | 功能 |
|---|---|
| `gateway.py` | Python 3.9+ 只读文件服务，HMAC 请求验证、HTTP Range（仅放开 /0 白名单，其余与 windows 版一致） |
| `gateway_check.py` | 网关单元测试，包含超 2GB 文件范围读取 |
| `gateway-linux.conf` | 监听 0.0.0.0、任意来源 IP、下载根目录、共享密钥 |
| `start-gateway-linux.sh` | 读取配置并以守护进程方式管理 Python（`start`/`stop`/`restart`/`status`），日志写入 `gateway.log` |

数据流：

```text
用户浏览器 -> ClipFetch Spring Boot 服务器
               -> HTTP + HMAC-SHA256 -> Linux 主机 :18766
                                        -> gateway.py
                                           -> qBittorrent 文件目录
```

浏览器不会直接访问文件网关；它访问 ClipFetch 的文件下载 URL，Spring Boot 校验用户及文件后再分块代理至 Linux 主机。

## 2. 部署步骤

1. 把整个 `local/` 目录上传到 qBittorrent 所在 Linux 主机。
2. 确认 Python 3.9 或更新版本：

```bash
python3 --version
```

3. 编辑 `gateway-linux.conf`：

```dotenv
QBT_FILE_ROOT=/srv/qbittorrent/downloads
QBT_FILE_GATEWAY_BIND=0.0.0.0
QBT_FILE_GATEWAY_PORT=18766
QBT_FILE_GATEWAY_ALLOW_REMOTE=true
QBT_FILE_GATEWAY_ALLOWED_IPS=0.0.0.0/0,::/0
QBT_FILE_GATEWAY_SECRET=<至少32字符的随机密钥>
```

- `QBT_FILE_ROOT` 必须是 Linux **宿主机实际的 qBittorrent 保存根目录**（Docker 部署时填宿主机挂载路径，不是容器内的 `/downloads`）。目录必须真实存在且可读。
- 密钥生成：`openssl rand -hex 32`，与 Spring Boot `backend/.env` 的 `QBT_FILE_GATEWAY_SECRET` 填同一个值。

4. 启动与日常管理（后台守护进程，不占用终端）：

```bash
chmod +x start-gateway-linux.sh
./start-gateway-linux.sh start      # 后台启动
./start-gateway-linux.sh status     # 查看运行状态
./start-gateway-linux.sh stop       # 停止
./start-gateway-linux.sh restart    # 修改 gateway-linux.conf 后重启生效
```

脚本会检查下载根目录、共享密钥长度，然后通过 `nohup` 后台启动 `gateway.py`，PID 写入同目录 `gateway.pid`，输出日志写入 `gateway.log`（`tail -f gateway.log` 实时查看）。它不会修改 qBittorrent 的下载任务或文件。

5. 防火墙 / 云安全组放行 TCP 18766 入站（例如 `sudo ufw allow 18766/tcp`）。**测试结束后记得收回该规则**。

## 3. Spring Boot 服务器环境变量

在 `backend/.env` 中配置（参数值均为示例）：

```dotenv
QBT_SAVEPATH=/downloads
QBT_FILE_GATEWAY_URL=http://<Linux主机IP>:18766
QBT_FILE_GATEWAY_SECRET=<与网关一致的随机密钥>
QBT_FILE_GATEWAY_ALLOW_HTTP=true
```

`QBT_SAVEPATH` 需要和 qBittorrent Web API 返回的保存根目录一致（Docker 下通常是容器内路径）；`QBT_FILE_ROOT` 是宿主机真实路径，两者不是同一概念。修改 `.env` 后重启后端。

## 4. 验证与排错

在 `local/` 目录运行单元测试：

```bash
python3 -m unittest -v gateway_check
```

无签名探测（网关已启动时预期 HTTP 403）：

```bash
curl -sS -o /dev/null -w '%{http_code}\n' 'http://127.0.0.1:18766/internal/files?path=test'
```

| 现象 | 优先检查 |
|---|---|
| 启动失败提示找不到目录 | `gateway-linux.conf` 的 `QBT_FILE_ROOT` 是否真实存在且可读 |
| 403 | 两端密钥是否一致、两端系统时间差是否超过 90 秒 |
| 404 | qBittorrent 实际文件路径、文件是否被移动或删除 |
| 连接拒绝 / 超时 | `gateway.py` 是否运行、防火墙/云安全组是否放行 18766 |
| 大文件下载中断 | 后端和网关支持 HTTP Range 单区间续传，换支持 Range 的下载方式重试 |
