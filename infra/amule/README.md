# aMule 3.2.0 (`amuleapi` `/api/v1`) Docker Compose 部署说明

本目录用于启动 ClipFetch 电驴（eD2k）下载所需的 `amuled + amuleapi` 外部引擎容器（镜像固定为 `ngosang/amule:3.2.0-1`）。

## 1. 安全与端口约定

| 容器端口 | 宿主机绑定 | 协议 | 说明 |
|---|---|---|---|
| `4711` | `127.0.0.1:${AMULE_API_HOST_PORT:-4711}` | TCP | `amuleapi` `/api/v1` REST 管理端口，**仅绑定本机 `127.0.0.1`**，禁止对外网暴露 |
| `4712` | 不映射到宿主机 | TCP | `amuled` EC 内部端口，仅容器内 `amuleapi -> 127.0.0.1:4712` 使用 |
| `4662` | `${AMULE_ED2K_TCP_PORT:-4662}` | TCP | eD2k 客户端传输端口 |
| `4665` | `${AMULE_ED2K_SERVER_UDP_PORT:-4665}` | UDP | eD2k 扩展服务器请求端口（TCP + 3） |
| `4672` | `${AMULE_KAD_UDP_PORT:-4672}` | UDP | Kad 网络端口 |

## 2. 目录挂载与 `allowed-remote-dirs.dat` 边界

- 配置目录：`${AMULE_CONFIG_ROOT:-./data}` -> `/home/amule/.aMule`
  - 存放 `amule.conf`、`amuleapi.conf`、`allowed-remote-dirs.dat`、Known/Kad/Server 节点信息与身份密钥。
  - **注意**：在 `amuleapi` 中，即使未配置 `allowed-remote-dirs.dat`，`/api/v1/shared/{hash}/content` 也始终允许访问 `Incoming` 目录下的已分享完成文件；严禁将宿主机敏感目录写入 `allowed-remote-dirs.dat`。
- 下载目录：`${AMULE_DATA_ROOT:-../../data/ed2k/downloads}` -> `/downloads`
  - 临时分片目录：`/downloads/temp`（宿主机 `${AMULE_DATA_ROOT}/temp`）
  - 已完成目录：`/downloads/incoming`（宿主机 `${AMULE_INCOMING_ROOT}`，即 `${AMULE_DATA_ROOT}/incoming`）
  - ClipFetch 后端通过 `AMULE_INCOMING_ROOT` 对已完成文件执行真实路径解析（`LinkOption.NOFOLLOW_LINKS`）、文件大小核验与 `Range` 流式读取。

## 3. 启动与排障命令

```powershell
# 启动 aMule 容器
docker compose -f infra/amule/docker-compose.yml up -d

# 查看容器状态与日志
docker compose -f infra/amule/docker-compose.yml ps
docker compose -f infra/amule/docker-compose.yml logs --tail=100 amule

# 验证本机 REST 健康检查与版本
curl.exe -s http://127.0.0.1:4711/api/v1/health
curl.exe -s http://127.0.0.1:4711/api/v1/version
```

## 4. 电驴（eD2k / Kad）下载速度优化指南

1. **获取 HighID（最核心提速手段）**：
   - 在路由器/防火墙上将公网入站 `4662/TCP`、`4665/UDP`、`4672/UDP` 转发到宿主机对应端口（注意：**绝不要**转发管理端口 `4711`）。
   - HighID 节点可连接全网 HighID + LowID 源；LowID 无法互联其他 LowID 节点，可用源数量通常损失 50% 以上。ClipFetch `/ed2k` 页面状态栏会实时显示 `eD2k HighID / LowID` 与 `Kad 已连接` 状态。
2. **ClipFetch 自动下发的高速参数（`PATCH /api/v1/preferences`）**：
   - **解除反吸血限速**：aMule 核心在上传限速 `1..9 KiB/s` 时会强制将下载速度限制为上传的 3x/4x；ClipFetch 默认配置 `AMULE_MAX_DOWNLOAD_KBPS=0`、`AMULE_MAX_UPLOAD_KBPS=0`（并将 `1..9` 自动抬升至 `>=10`），配合 `AMULE_UPLOAD_SLOT_MIN_KBPS=10` 加速信用积分（Credit）累积。
   - **扩大寻源与连接并发**：默认下发 `max_connection_count=800`、`max_sources_per_file_count=600`、`max_new_connections_per_5_seconds=60`、`kad_max_concurrent_source_search_count=25`。
   - **协议混淆与尾盘冲刺**：默认开启 `protocol_obfuscation_enabled=true` + `obfuscation_requested=true`（绕过运营商 DPI 限速）、`endgame_mode_enabled=true`（最后分片多源并发避免 99% 卡顿）、`file_buffer_bytes=1500000`（1.5MB 内存写缓冲降低多源随机写磁盘停顿）。
3. **单任务高优先级与 A4AF 空闲源抢占**：
   - 新建/恢复任务自动设置 `priority=high` 与 `a4af_auto=true`；点击页面「加速寻源」按钮可立即调用 `POST /api/v1/downloads/{hash}/a4af` (`{"action":"swap_this"}`) 将停留在其他任务上的空闲 A4AF 源切换到当前任务。
