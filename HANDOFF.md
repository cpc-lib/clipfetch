# HANDOFF.md

> 最后更新：2026-11-07（第六轮）
> 本轮目标：实现「先加载 backend/.env，再由根 .env 覆盖同名键」。
> 代码：`ClipFetchApplication.loadDotEnv` 增加 override 参数与 `DOTENV_KEYS` 跟踪集——根 `.env` 仅覆盖「此前由 .env 写入」或「未设置」的键，永不覆盖 OS 环境变量与 `-D` 参数；优先级定为 OS env / -D > 根 .env > backend/.env > yml 默认值。AGENTS.md 配置节已写入该规则。
> 关键风险处置：逐键比对发现根 .env 有 11 个值与 backend/.env 不一致且均为陈旧值（FTP_ENABLED=false、FTP_TRUSTSTORE_PATH=空、FTP_ALLOWED_TARGETS 旧端口、FTP_CREDENTIAL_KEY/KEY_ID 不一致、MAGNET_V2/TORRENT_NATIVE/QBT 旧路径、DOWNLOADS_DIR 相对路径、FTP_ALLOW_PLAINTEXT、FTP_TRUSTSTORE_PASSWORD），覆盖生效会直接禁用 FTP、致 FTPS 失败、已存凭据无法解密。已将这 11 键在根 .env 中对齐为 backend/.env 当前值（未打印密钥内容，脚本按键名同步）。
> 验证：`mvn -q -pl backend compile` Exit 0；运行时探针：根 .env 临时加 `LOGS_DIR=.../logs-override-test` 启动后端，日志仅写入探针目录（旧 logs/clipfetch.log 时间戳停留在上一轮 12:14 未变），证明覆盖生效；随后恢复根 .env 并删除探针目录，后端已停止。
> 本轮：`downloads\magnets-v0` 改名为 `downloads\magnet-v0`（目录本为空、无容器占用，直接 Rename-Item）。同步修改 4 处引用：`backend/.env` 与 `.env.example` 的 `QBT_DOWNLOADS_HOST_DIR`、`infra/qbt-file-gateway/{windows,prod/windows}/gateway-windows.conf` 的 `QBT_FILE_ROOT`。全仓扫描（含被 gitignore 的文件）已无 magnets-v0 残留。注意：根 `.env` 该键值为 `D:/BaiduNetdiskDownload`（历史遗留，根 .env 不被后端读取，未改动）；qbittorrent 容器当前不存在，重建时按新路径挂载即可。
> 另：本轮前已完成 3 个提交并推送 origin/feature（b8ef097 feat(ftp)、f9307bc feat(ed2k)、78eb775 chore）；amule config 目录因含凭据哈希已整体 gitignore（仅保留 allowed-remote-dirs.dat）。
> 本轮目标：修复三个 env 文件「key 数量不一致」。
> 结论：活跃键本就一致（各 205 个，集合相等），用户看到的是行数/注释/顺序不一致（348/373/355 行，根 .env 曾把 AMULE_/FTP_ 追加在文件末尾）。修复：以 `.env.example` 为骨架重排根 `.env` 与 `backend/.env`——键行取各自原值原行，注释/空行/顺序全部沿用 example。验证：三文件均 373 行、205 键、键集合与顺序完全一致；抽查各文件自有值（DB_URL、DOWNLOADS_DIR、LOGS_DIR、FTP_TRUSTSTORE_PATH）均保留；三文件均无 BOM。
> 本轮目标：后端日志统一落盘到唯一 logs 目录（含启动日志）。
> 改动：三处 env 文件 `LOGS_DIR=logs`（相对路径，随启动 CWD 漂移，导致日志分散在 `backend/logs` 与仓库根 `logs` 两处）改为绝对路径 `LOGS_DIR=d:/release/clipfetch/logs`（.env.example 附注释说明为何用绝对路径）。清理旧目录：`backend/logs/`（含 2 个 .gz 归档）整体删除，根 `logs/` 旧 .gz 归档删除。
> 验证：以仓库根为 CWD 启动 `mvn -pl backend spring-boot:run`，日志实际写入 `D:\release\clipfetch\logs\clipfetch.log`，且包含完整启动行（Starting → Tomcat started → Started ClipFetchApplication）；`backend/logs` 未再生成。验证后已停止后端，8082 空闲。logback 滚动策略不变（按天 + 单文件 100MB，保留 30 天，上限 2GB）。
> 本轮目标：应用户要求在 AGENTS.md 写入「三处 .env 变量名一致性」规则，并把根 `.env` 补齐到位。
> 本轮改动：(1) AGENTS.md「### 配置」新增规则：`backend/.env`、根 `.env`、`.env.example` 键集合必须完全相同，新增/重命名/删除配置项三处同步，`.env` 只许改值不许少键，禁止「example 有而 .env 无」静默走 yml 默认值；(2) 根 `.env` 原 140 键、缺全部 AMULE_*/FTP_* 共 65 键，已按 .env.example 键值追加补齐（UTF-8 无 BOM 写入）。验证：三处均为 205 键、集合完全一致、三文件均无 BOM。
> 上一轮：核对 `.env` 配置并补齐 backend/.env 缺失的 18 个 FTP_* 键，yml 三处默认值对齐 example（20→3 / 8→4 / 4→2）。
> 前几轮已完成：FTP/FTPS 远端删除功能；FTP 控制通道 UTF-8 修复（中文文件名）；移除 backend/src/test 与 fixture 遗留文件并更新 AGENTS.md/README.md；README 补 FTP/FTPS 测试服务连接信息。

## 1. 核对结果与改动

核对口径：`.env.example`（205 键）↔ `backend/.env`（187 键）↔ `application.yml` 占位符（218 个）。

- **backend/.env 缺 18 个 FTP_* 键**（example 有、.env 无）：`FTP_MAX_FILE_BYTES` / `FTP_MAX_ACTIVE_TASKS_PER_USER` / `FTP_MAX_CONCURRENT` / `FTP_MAX_CONCURRENT_PER_USER` / `FTP_QUEUE_CAPACITY` / `FTP_CONNECT_TIMEOUT_MS` / `FTP_READ_TIMEOUT_MS` / `FTP_TRANSFER_IDLE_TIMEOUT_MS` / `FTP_TRANSFER_DEADLINE_MS` / `FTP_RETRY_MAX` / `FTP_RETRY_INITIAL_MS` / `FTP_RETRY_MAX_MS` / `FTP_CHECKPOINT_BYTES` / `FTP_CHECKPOINT_INTERVAL_MS` / `FTP_FILE_BUFFER_BYTES` / `FTP_ANONYMOUS_USERNAME` / `FTP_ANONYMOUS_PASSWORD` / `FTP_CONNECTION_TEST_COOLDOWN_MS`。缺失时静默生效 yml 默认值。
- **3 个键 yml 默认值与 example 不一致**（已改 yml 对齐 example）：`max-active-tasks-per-user` 20→3、`max-concurrent` 8→4、`max-concurrent-per-user` 4→2。注意：并发/任务上限因此收紧，符合 example 既定设计。
- 已把 18 个键按 example 默认值追加到 `backend/.env` FTP 段（第 338-355 行），未触碰任何密钥值。

验证：修复后 example 205 键 = backend/.env 205 键，无缺失、无多余；`backend/.env` 首三字节 `23 20 3D` 无 BOM。

## 2. 仍存在的缺口（本轮未改）

- `application.yml` 有 14 个占位符在 `.env.example` 中没有条目（仅靠 yml 默认值生效）：`FLYWAY_LOCATIONS`、`MULTIPART_MAX_FILE_SIZE`、`MULTIPART_MAX_REQUEST_SIZE`、`PYTHON_EXE`、`YOUTUBE_MIRROR_APIS`、`MAGNET_V2_METADATA_POLL_MS`、`QQMUSIC_SIDECAR_ENABLED/_SCRIPT`、`KUGOU_SIDECAR_ENABLED/_SCRIPT`、`TENCENT_SIDECAR_ENABLED/_SCRIPT`、`SPANKBANG_SIDECAR_ENABLED/_SCRIPT`。其中 `PYTHON_EXE`、`YOUTUBE_MIRROR_APIS` 已在 README 配置表中文档化，建议补进 `.env.example`。
- 另发现 `application.yml` 含 `SPANKBANG_SIDECAR_*` 成人站点相关占位符，与 AGENTS.md 第 8 节禁止事项冲突；系历史遗留，未擅动，待用户确认后清理。

## 3. 当前运行状态

- 后端 8082、前端 5173 均未运行。配置类改动需重启后端生效：`cd backend; $env:SERVER_PORT='8082'; mvn spring-boot:run`；前端 `cd frontend; npm run dev`。
- 容器：nv2-* healthy；`clipfetch-amule`、`qbittorrent`、`clipfetch-dev-ftp`、`clipfetch-dev-ftps` Up。

## 4. 遗留事项

- FTPS 覆盖上传已存在文件返回 422「FTPS 数据通道未使用 TLS」（新文件名上传正常，未排查）。
- 仓库无单元测试源码（已按用户要求移除）。
- 本轮未创建 commit。
