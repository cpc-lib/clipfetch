# libtorrent gRPC Sidecar Worker

C++ 实现的 BitTorrent 下载引擎 Sidecar，通过 gRPC 向 Java 控制面（`GrpcLibtorrentEngine`）提供下载能力。
基于 Debian bookworm 原生包：libtorrent-rasterbar 2.0.8 + gRPC + protobuf，cmake + ninja 构建。

## 目录结构

```
native/
├── Dockerfile                  # 构建 + 运行镜像（debian:bookworm-slim）
├── libtorrent-worker/
│   ├── CMakeLists.txt          # protoc 生成链 + 链接配置
│   └── src/main.cpp            # DownloadEngine 服务实现
└── proto/
    └── download_engine.proto   # Java ↔ C++ 契约（package cc.ivera.magnet.engine.v1）
```

## 构建镜像

```powershell
docker build -t magnet-libtorrent-worker:test -t magnet-libtorrent-worker:latest native
```

> build context 必须是 `native/`（Dockerfile 需要同时 COPY `proto/` 和 `libtorrent-worker/`）。
> 编译产物固定为 `/workspace/build/libtorrent-worker`。

## 运行

### 方式一：docker compose（推荐，与 MySQL/Redis 一起）

```powershell
docker compose -f infra/docker-compose.yml up -d
```

### 方式二：docker run（临时调试）

```powershell
docker run -d --name magnet-worker `
  -p 127.0.0.1:50051:50051 `
  -p 6881:6881 -p 6881:6881/udp `
  -v worker-data:/data `
  -v worker-locks:/locks `
  magnet-libtorrent-worker:test
```

`/data` 必须挂载为持久化 Worker 下载根；容器内 `--download-root=/data` 由镜像默认命令传入。Java 的 `AddTorrentRequest.storage_path` 必须是 `/data` 下的绝对路径，或相对于该根的路径；空路径、根外绝对路径、软链接逃逸路径和规范化后逃逸的 `..` 路径都会被拒绝。若使用 bind mount，宿主目录必须映射到 `/data`，例如 `-v d:\magnet-data:/data`。

P5 进一步要求资源路径与 `resources/{resource_id}` 一致，防止不同资源锁指向同一物理目录。
所有共享数据的 Worker 必须共用 Linux Docker 命名卷 `/locks`（默认 `LOCK_ROOT=/locks`），
不能用每个容器独立的匿名锁卷。每个请求均需 `owner_epoch`、`deadline` 和 `lease_until`；
租约截止时间由 API 从 MySQL UTC 租约传入，Worker 不自行延长授权。
旧协议客户端不能与新版 Worker 混用，部署步骤参见根 README 的 P5 章节。

锁在进程退出或 libtorrent 明确确认资源移除已排空后才释放。租约到期会终止整个 Worker，
防止后台磁盘线程在失去所有权后继续写入。Epoch 下限持久保存在锁卷中，不随容器重启丢失。
元数据会话不允许下载负载或删除数据，解析成功且移除确认后释放锁。

真实双容器回归：`docker build -t magnet-libtorrent-worker:ha-test native` 后运行
`python native/tests/test_ha.py`（需安装 `grpcio grpcio-tools`）。覆盖独占锁、过期命令、
容器崩溃接管、旧 Epoch 重放、进程冻结、租约超时、损坏文件重校验和删除确认。

端口说明：

| 端口 | 用途 |
|---|---|
| 50051 | gRPC（Java 控制面连接） |
| 6881 TCP/UDP | DHT / BT peer 通信 |

启动成功的日志标志：

```
[worker] 启动 gRPC 服务监听 0.0.0.0:50051 download-root="/data"
[worker] gRPC 服务已就绪，等待 Java 控制面连接
```

## 验证 Health RPC

```powershell
python -c "
import sys, os; sys.path.insert(0, os.environ['TEMP'] + '\grpc-test')
import grpc, download_engine_pb2, download_engine_pb2_grpc
ch = grpc.insecure_channel('localhost:50051')
stub = download_engine_pb2_grpc.DownloadEngineStub(ch)
r = stub.Health(download_engine_pb2.HealthRequest())
print(f'ok={r.ok} worker={r.worker_id} libtorrent={r.libtorrent_version}')
"
```

预期输出：`ok=True worker=local-worker-1 libtorrent=2.0.8`

（Python stub 生成：`python -m grpc_tools.protoc --proto_path=proto --python_out=%TEMP%\grpc-test --grpc_python_out=%TEMP%\grpc-test proto/download_engine.proto`）

## Java 侧接入

`server/download-api/.env` 中：

```properties
MAGNET_GRPC_ENABLED=true          # true=本 Worker，false=MockLibtorrentEngine
MAGNET_GRPC_ENDPOINT=localhost:50051
MAGNET_GRPC_INSECURE=true
```

Spring Boot 启动日志出现 `[grpc-libtorrent] Worker 就绪 ok=true` 即全链路贯通。

## 已实现 RPC

| RPC | 状态 |
|---|---|
| Health | ✅ |
| ResolveMetadata | ✅（元数据就绪时返回 .torrent 字节，未就绪返回 `PENDING`） |
| AddTorrent | ✅（torrent bytes / info hash / resume data；校验 `storage_path`） |
| UpdateFilePriorities | ✅（按文件下标下发优先级；缺失 `resource_id` 返回 `accepted=false`） |
| GetSnapshot | ✅（状态、总进度和每个文件的 `file_verified_bytes`） |
| SaveResumeData | ✅（返回非空 resume bytes 和递增 `resume_version`） |
| Pause | ✅（缺失 resource_id 幂等成功） |
| Resume | ✅（缺失 resource_id 幂等成功） |
| RemoveTorrent | ✅（缺失 resource_id 幂等成功；`remove_unreferenced_data=true` 删除文件） |
| StreamAlerts | ⚠️ 当前不在本 Task 3 范围内 |

## 已知简化项

- `engineSessionId = resourceId`（UUID 字符串），会话存进程内 map，进程 crash 丢失——由 Java 控制面按 owner_epoch 重建（规格 §12.1 允许）。
- `OWNER_EPOCH` 硬编码为 1（单 Worker）；多 Worker 部署时需改为 DB 租约。
- Debian bookworm 的 libtorrent-rasterbar 2.0.8 没有同步 `get_resume_data`；`SaveResumeData` 使用 `save_resume_data(save_info_dict)`，等待 `save_resume_data_alert`，再用 `write_resume_data_buf` 序列化。若未来头文件提供同步 API，C++20 helper 会在编译期选择同步分支。
- `StreamAlerts` 仍未实现；其余上表 RPC 走正常 `accepted/error` 响应。
