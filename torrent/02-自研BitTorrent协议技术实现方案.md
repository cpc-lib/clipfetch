# ClipFetch 自研 BitTorrent 协议引擎技术实现方案

> 版本：1.0 · 日期：2026-10-14 · 状态：待实施的技术设计（不是已经完成的功能）
> 部署定位：嵌入现有 Spring Boot 单体进程；独立协议包；不依赖 qBittorrent/libtorrent/aria2/迅雷执行新任务。
> 第一阶段：BitTorrent v1、TCP Peer、HTTP/UDP Tracker、DHT、ut_metadata、按用户任务隔离、按文件下载。
> 第二阶段：PEX、IPv6、性能增强；第三阶段：uTP、BT v2/hybrid（BEP52）。
> 前置：遵守项目根目录 AGENTS.md；不得新建微服务、Redis、MQ、cron、前端路由。

## 1. 设计决策（ADR）

| ADR | 决策 | 理由 |
|---|---|---|
| A01 | 单一 Spring Boot 部署；cc.ivera.magnet.engine 包 | 与当前仓库单体硬约定一致 |
| A02 | 首期使用 Java 21 NIO Selector/DatagramChannel/FileChannel | 真实纯 Java 协议自研，不必引入 BT 库或 Netty |
| A03 | BT v1 首发，拒绝未支持的仅 v2 Magnet | 防止错误套用 v1 的 SHA-1/Piece 布局 |
| A04 | 每 taskUuid 拥有独立 PeerSession、PieceScheduler、存储、进度 | 支持同 Hash 不同用户并发和独立暂停 |
| A05 | 只共享已验证不可变元数据与 DHT/Peer discovery 基础设施 | 节省发现成本，不引入控制面串扰 |
| A06 | native API 永远按 taskUuid 操作，不按 infoHash 操作 | 解决现有全局唯一 Hash 的设计瓶颈 |
| A07 | native 新任务使用独立表；legacy qBittorrent 数据结构不修改 | 可共存、可回滚 |
| A08 | 任务落盘路径是存储根/userId/taskUuid | 防止跨用户覆盖/删除 |
| A09 | 进度按“所选逻辑文件的已验证并持久化字节”计算 | 不是整个种子完成度，也不是网络收包字节数 |
| A10 | SQLite/MySQL 双库迁移、XML Mapper、JWT userId 来源后端 | 满足现有约束 |
| A11 | 前端继续 Vue3 + axios + Tailwind，进度采用原有轮询模式 | 不引入新依赖，降低首版复杂度 |
| A12 | 无跨用户 Piece 内容去重（后续研究） | 用户生命周期独立最容易证明 |

**重要限制：**这实现的是应用层和存储层隔离。由于任务共用操作系统、CPU、磁盘、网络出口 IP，并不能等同于每用户独立物理网络出口。带宽公平由资源调度实现。

## 2. 总体架构

~~~mermaid
flowchart TD
  UI["ClipFetch Vue 3<br/>磁力解析 / 文件选择 / 用户任务进度"] --> API["MagnetController / NativeTorrentController<br/>JWT + currentUserId + ApiResponse"]
  API --> SVC["TorrentApplicationService<br/>鉴权 / 任务创建 / 路由 / 配额"]
  SVC --> REPO["MyBatis XML / SQLite or MySQL<br/>torrent_task / torrent_task_file / metadata"]
  SVC --> MGR["TorrentEngineManager"]
  MGR --> DIS["共享 DiscoveryService<br/>HTTP/UDP Tracker + DHT Routing"]
  MGR --> META["共享已校验 MetadataStore<br/>Bencode / ut_metadata"]
  MGR --> T1["TaskActor A (user A, UUID A)<br/>PeerSession + Scheduler + Bitmap"]
  MGR --> T2["TaskActor B (user B, UUID B)<br/>PeerSession + Scheduler + Bitmap"]
  DIS --> T1
  DIS --> T2
  META --> T1
  META --> T2
  T1 --> F1["用户 A 专属目录 / Checkpoint"]
  T2 --> F2["用户 B 专属目录 / Checkpoint"]
  API --> TICKET["TicketService / NativeFileStreamer<br/>Bearer 换短时票据，Range"]
~~~

### 2.1 任务边界

~~~text
同一 InfoHash H:
  taskUuid=A1, userId=1001, files={0}, status=PAUSED,
  selectedTotal=1GiB, verifiedSelected=350MiB, path=.../1001/A1/

  taskUuid=B1, userId=1002, files={1,2}, status=DOWNLOADING,
  selectedTotal=4GiB, verifiedSelected=2GiB, path=.../1002/B1/

  taskUuid=A2, userId=1001, files={0,2}, status=DOWNLOADING,
  path=.../1001/A2/

三者仅 infoHash / 只读 metadata 可相同。
每个任务连接池、调度器、Piece 位图、进度、速率、文件目录互不引用。
~~~

**不得实现** Map<InfoHash,TorrentTask> 或以 infoHash 作为任务级锁。正确形式是 Map<UUID,TaskActor>；不可变元数据缓存另用 Map<NormalizedInfoHash,VerifiedMetainfo>。同 Hash 的多用户/多任务拥有独立下载会话，避免单个上传/下载会话内取消请求影响其他任务。

## 3. 项目代码落点

~~~text
backend/src/main/java/cc/ivera/magnet/
├── interfaces/
│   ├── MagnetController.java                 # legacy 入口继续保留
│   └── NativeTorrentController.java          # 新增 UUID 原生引擎接口
├── application/
│   ├── DownloadTicketStore.java              # 已存在，扩展时不破坏旧行为
│   └── TorrentApplicationService.java        # 认证后的业务编排
├── domain/
│   ├── TorrentTask.java                      # 实体；数值 id+业务 UUID
│   ├── TorrentTaskFile.java
│   ├── TorrentMetadata.java
│   ├── TorrentTaskMapper.java                # Mapper 仅接口
│   └── TorrentMetadataMapper.java
└── engine/
    ├── api/
    │   ├── TorrentEngine.java
    │   ├── TaskHandle.java
    │   └── EngineEvent.java
    ├── magnet/
    │   ├── MagnetUriParser.java
    │   └── InfoHash.java
    ├── codec/
    │   ├── BencodeDecoder.java
    │   ├── BencodeEncoder.java
    │   └── ByteString.java
    ├── metainfo/
    │   ├── TorrentV1MetainfoParser.java
    │   ├── PieceLayout.java
    │   ├── FileSpan.java
    │   └── VerifiedMetadataStore.java
    ├── tracker/
    │   ├── HttpTrackerClient.java
    │   ├── UdpTrackerClient.java
    │   └── TrackerAnnounce.java
    ├── dht/
    │   ├── DhtNode.java
    │   ├── KBucketRoutingTable.java
    │   ├── KrpcCodec.java
    │   └── IterativePeerLookup.java
    ├── peer/
    │   ├── PeerWireCodec.java
    │   ├── PeerConnection.java
    │   ├── PeerReactor.java
    │   ├── PeerSelectionPolicy.java
    │   └── ExtensionProtocol.java
    ├── metadata/
    │   └── UtMetadataDownloader.java
    ├── task/
    │   ├── TorrentEngineManager.java
    │   ├── TaskActor.java
    │   ├── TaskStateMachine.java
    │   ├── PieceScheduler.java
    │   ├── BlockRequestTracker.java
    │   ├── TaskProgressCalculator.java
    │   ├── UserQuotaManager.java
    │   └── PeerRateSampler.java
    ├── storage/
    │   ├── SafeTaskWorkspace.java
    │   ├── V1PieceFileMapper.java
    │   ├── PieceVerifier.java
    │   ├── BoundaryPieceCache.java
    │   ├── ResumeCheckpointStore.java
    │   └── NativeFileStreamer.java
    └── config/
        └── TorrentEngineProperties.java

backend/src/main/resources/mapper/
├── TorrentTaskMapper.xml
└── TorrentMetadataMapper.xml

backend/src/main/resources/db/migration/
├── mysql/V13__torrent_native_engine.sql      # 示例版本；实际先检查占用
└── sqlite/V13__torrent_native_engine.sql     # 同一迁移版本号
~~~

与已有 QBittorrentClient、RemoteTorrentFileGateway 保持解耦；新 native 下载路径不调用 qBittorrent API，legacy 路径不调用 native session。

## 4. 核心接口契约

### 4.1 引擎接口（Java 21，设计示例）

~~~java
public interface TorrentEngine {
    ParseHandle parse(ParseCommand command);   // 磁力解析，异步
    TaskHandle create(CreateTaskCommand command);
    TaskSnapshot get(UUID taskUuid);
    void start(UUID taskUuid);
    void pause(UUID taskUuid);
    void delete(UUID taskUuid, boolean deleteFiles);
    List<FileSnapshot> files(UUID taskUuid);
    void shutdownGracefully();
}

public record CreateTaskCommand(
    long userId, long parseId, Set<Integer> selectedFileIndexes,
    UUID taskUuid
) {}

public record TaskSnapshot(
    UUID taskUuid, long userId, String infoHash, TaskStatus status,
    long selectedTotalBytes, long verifiedSelectedBytes,
    long downloadedWireBytes, long downloadSpeedBytesPerSec,
    Long etaSeconds, long revision
) {}
~~~

注意：userId 由应用服务通过认证上下文注入，CreateTaskCommand 不从 HTTP 请求 JSON 直接反序列化。Engine 内部依然执行防御性归属校验，不允许仅凭 UUID 暴露数据。

### 4.2 原始协议 API 与业务 API 分离

- 引擎内部：PeerAddress、InfoHash、PieceIndex、BlockIndex；网络协议层完全不知道业务 userId。
- 编排层：taskUuid 映射 userId + infoHash + selectedFiles，并拥有独立 Session。
- HTTP 接口：永远由已认证 userId 定位该 UUID；不要提供无用户校验的按 hash 删除/暂停入口。
- DHT 和元数据缓存：只保存匿名网络/资源发现信息，**绝不存储** JWT、用户路径、文件下载进度。

## 5. BitTorrent v1 协议实现

### 5.1 Magnet & InfoHash

输入：magnet:?xt=urn:btih:<hash>&tr=<url>&dn=<name>。
- 识别 btih 的 40 位十六进制以及 32 字符 Base32，统一转换为 20 原始字节、40 位小写 hex。
- 同链接可有多个 tr；保持顺序去重。dn 仅用作未经验证的 UI 提示，不当作可信文件名。
- 对 btmh（BT v2）仅识别并返回清晰的 UNSUPPORTED_V2，不能错用 SHA-1 算法。
- 对传入的 Tracker 地址进行 scheme、DNS/IP、端口和重定向安全校验，拒绝 loopback、private、link-local 等受保护地址（受控测试环境通过独立测试配置放行）。
- 限制 Magnet 长度、Tracker 数量、每个字段长度和重复参数数量。

### 5.2 Bencode（BEP 3）

支持整数、字节串、list、dict；字典键按原始字节序；网络协议二进制字段**不得**强行转换为 Java String。
- Decoder 使用 bounded ByteBuffer 和递归深度上限，所有长度运算使用 long 加溢出检查。
- 文件名与路径单独编码验证，禁止 "."、".."、绝对路径、Windows 保留设备名、非法分隔符、NUL、控制字符与 Unicode 规范化碰撞。
- torrent v1 InfoHash = SHA-1(原始 bencoded info 字典字节范围)，保留原始范围；非法编码必须拒绝或明确按规范处理。
- 解析 pieces（二进制 SHA-1 数组）、piece length、length/files、announce / announce-list；检查 Piece 数与总字节数匹配。

### 5.3 Tracker（BEP 3 / 15）

HTTP(S) Tracker：
- 使用项目已有 JDK HttpClient；GET announce 的 info_hash/peer_id 必须按原始字节 percent-encoding。
- 识别 compact/non-compact peers、interval、min interval、failure reason；解析 peer IPv4/IPv6（首期下载 IPv4）。
- 每次 HTTP 跳转重新校验目的 IP 和目标端口；限制响应体大小和请求超时。

UDP Tracker：
- DatagramChannel，首先 connect 获取 connection_id，之后 announce，校验 action / transaction_id。
- 支持规范重传与 connection_id 失效重试；peer 列表去重，异常包丢弃。
- announced / completed / stopped 以真实状态驱动；同一 InfoHash 下多个独立任务有各自 peer_id、事件计数。

### 5.4 DHT（BEP 5）

- UDP KRPC：ping、find_node、get_peers；首期无需实现公网 Tracker 服务。条件允许时完整处理 announce_peer 与入站请求。
- 单引擎 DHT Node ID（160 bit），XOR 距离，K-bucket 路由表，失效节点探测和 bootstrap。
- get_peers 迭代查找遵守并发上限与超时；compact nodes/values 必须按协议长度验证。
- **共享 DHT 路由表，任务订阅只读 Peer 候选**。关闭任务不会清空 DHT，也不会取消其他任务的 peer discovery。
- 对外只输出合法目标 IP/端口的候选集合；不向浏览器暴露 Peer IP。

### 5.5 Peer Wire（BEP 3）

首期基于 TCP SocketChannel + Selector：
- 握手：pstrlen、"BitTorrent protocol"、8 字节 reserved、20 字节 info_hash、20 字节 peer_id。
- 消息：keep-alive、choke、unchoke、interested、not interested、have、bitfield、request、piece、cancel、port。
- 严格验证 length prefix、message ID、索引边界、block offset/size，丢弃超长/错序报文。
- 处理半包/粘包：以 ByteBuffer 累积流，直到完整帧可读才派发，不假设单次 read 到完整消息。
- 每任务自己的 PeerConnection 集合，任务暂停仅关闭自己连接；不同任务共用 Selector 执行器，但 taskUuid 路由与所有权固定。

### 5.6 扩展协议与 Metadata（BEP 10 / 9）

- 握手 reserved 扩展位，extended message (message id = 20)；根据远端的 m.ut_metadata 获取其动态扩展消息 ID。
- 获取 metadata_size（加严格上限，例如 8 MiB）；按 BEP9 的 16 KiB 元数据片请求并重组。
- 处理 request/data/reject、同块去重、超时、重试、多 Peer 数据不一致。
- 收齐后提取 info 原始字节并验证 SHA-1 与请求 btih 相同；不匹配丢弃，不向前端展示。
- 验证通过后构建不可变 TorrentV1Metainfo，按 InfoHash 缓存；metadata cache 可跨用户共享，但用户解析历史仍隔离。

### 5.7 PEX、uTP、BEP52 的范围

| 功能 | 版本 | 原因 |
|---|---|---|
| PEX（BEP11） | P6 | 可提高可发现 Peer 数量，不影响基础闭环 |
| IPv6 Peer / DHT | P6 | 添加双栈地址/过滤策略及测试 |
| uTP（BEP29） | P7 | UDP 可靠传输和拥塞控制复杂 |
| BT v2/hybrid（BEP52） | P7 | SHA-256/Merkle Tree、不同文件对齐/哈希语义必须单独实现 |
| NAT-PMP/UPnP | P7 或可选 | 对外开放端口有额外安全风险，须单独评审 |

## 6. Piece 调度、下载与文件选择

### 6.1 分片映射

Torrent v1 全局偏移由文件清单按顺序串接：
~~~text
file0 = [0, 1000)
file1 = [1000, 2400)
file2 = [2400, 5000)
pieceLength = 1024
piece i = [i*1024, min((i+1)*1024, totalTorrentBytes))
selectedFiles={file1}
wantedPieceIndexes = {0,1,2}（因为 piece0/2 与 file1 边界交叠）
~~~
上述只是偏移示例，**不要把整块 1024 字节都算成 file1 的已下载字节**。

映射公式：
~~~text
selectedRanges = union([fileStart, fileEnd) for each selected file)
pieceRange(i) = [i*pieceLength, min((i+1)*pieceLength, torrentTotal))
wantedPieces = { i | intersection(pieceRange(i), selectedRanges) != empty }

selectedTotalBytes = sum(length(file) for selected files)
verifiedSelectedBytes =
    sum_i sum_{selectedRange r} length(intersection(pieceRange(i), r))
    for each i in durableVerifiedPieces
progressPercent = selectedTotalBytes == 0 ? 100 :
    clamp(100 * verifiedSelectedBytes / selectedTotalBytes, 0, 100)
~~~
仅在某 Piece 的完整哈希校验、选中部分写入并持久化成功、Checkpoint 提交后将该 Piece 计为 durableVerified。使用整数 byte 计算，再转 double 生成 UI 百分比。**网络下载的 unverified/block 重试字节不得作为进度分子**。

### 6.2 PieceScheduler

1. 从 Bitfield/Have 收集当前任务各 Piece 的可用 Peer 数。
2. 首期 random-first + rarest-first：优先较稀缺而可下载的 wanted Piece，避免堵在单 Peer。
3. 将 Piece 切分为有界 Block 请求（常见 16 KiB；参数可配置），同一任务维护 inFlightRequests。
4. 超时和断链收回请求；choked peers 不继续派发下载请求。
5. 接近末尾启用 Endgame：允许同 Block 少量重复请求，首次成功后向其他 Peer 发 cancel。
6. Piece 数据完整后独立线程校验 SHA-1，哈希失败标记失败并重排；不能写入已完成状态。
7. 有界连接/有界请求队列防止内存积压：per task + per user + global 三级配额。

### 6.3 上传与公平性

标准 BitTorrent 客户端需处理对方 request 并可按规则上传自己具备的已验证 Piece，以及 choking/unchoking 调度，否则互操作性和下载效率下降。严禁读取不属于该 taskUuid 已验证的文件字节来响应 Peer。上传配额、连接上限和是否继续做种按任务显式策略执行；首期优先完成已选择文件的下载。

## 7. 存储引擎、边界缓存与恢复

### 7.1 推荐磁盘布局

~~~text
TORRENT_NATIVE_STORAGE_ROOT/
└── users/
    └── 1001/
        └── tasks/
            └── 48e2...task-uuid/
                ├── files/                   # 用户选中的结果文件（相对路径）
                │   └── folder/selected.bin
                ├── .state/
                │   ├── resume.chk           # 原子生成 + 校验和 + 版本号
                │   └── resume.tmp
                └── .piece-cache/
                    └── boundary.bin         # 与未选文件重叠的 piece 必需的非选区间
~~~

未选文件不创建目标文件。跨选/未选文件的 Piece 必须下载整 Piece 才能验证 SHA-1，未选区间保存在任务私有边界缓存或可在恢复时重新拉取。为了**崩溃后准确验证**，首版选择保留必要边界字节，避免把无法重算 hash 的 Piece 错判为已验证。

正常 Piece 数据校验成功后将选中区间定位写入 FileChannel（并在边界缓存保留必要的未选区间）。对不包含未选区间的 Piece 不做额外双份暂存，避免全量磁盘放大。

### 7.2 持久化提交顺序

~~~text
RECEIVE BLOCKS（内存临时区）
    -> PIECE_COMPLETE
    -> SHA1_VERIFY_OK
    -> FileChannel.write(选中部分) + BoundaryCache.write(必要非选字节)
    -> FileChannel.force(...) + BoundaryCache.force(...)
    -> checkpoint temp 写入（含 piece 位图/revision/checksum）
    -> fsync/force checkpoint，再原子替换（平台不支持则用可回退的双版本）
    -> DB 按 revision CAS 更新 snapshot
    -> 发布 VERIFIED_PIECE 事件 / UI 进度更新
~~~

持久化顺序不可倒置。进程崩溃后，以**可验证的磁盘事实和 checkpoint** 重建快照；数据库仅为业务索引和最近显示快照，不能在磁盘数据缺失时宣称完成。脏退出时对已标记 Piece 逐块 SHA-1 校验；不完整/损坏块清位并重新请求，处理意外更改/文件截断。

### 7.3 ResumeCheckpoint 结构

~~~text
version
taskUuid
infoHash
metainfoSha256
pieceLength
pieceCount
selectedFileIndexes
durableVerifiedPieceBitmap
boundaryCacheIndex
revision
lastDurableUpdateEpochMillis
checksum
~~~
使用二进制结构加 CRC 或 SHA-256 校验 checkpoint，自身损坏时回退上一代；不得仅凭 DB 的 progressPercent 恢复。关闭任务/进程时刷盘，异常关闭靠重校验兜底。

### 7.4 安全删除与取流

- 删除任务默认仅从任务管理移除，原数据保留；用户显式 deleteFiles=true 才删除**自身 taskUuid** 目录。
- 先原子状态转 DELETING、取消自己网络请求并关闭所有 FileChannel，然后移除该任务文件。
- 删除动作路径必须根据服务端持久化的 userId+taskUuid 重新构造，禁止从请求参数或第三方元数据提供绝对路径。
- 允许的目标路径必须仍位于已配置 storage root 下，且不是符号链接跳转目标。
- 下载票据绑定 userId+taskUuid+fileIndex、短 TTL、可撤销；支持浏览器 Range、HEAD，拒绝未完成文件及未选文件。
- legacy QBT 的 RemoteTorrentFileGateway 不用于 native 本机文件取流；native 用自身的 NativeFileStreamer。

## 8. 多用户任务状态机与并发模型

### 8.1 状态

~~~mermaid
stateDiagram-v2
    [*] --> CREATED
    CREATED --> WAIT_METADATA
    WAIT_METADATA --> READY: metadata hash valid
    WAIT_METADATA --> FAILED: timeout / invalid
    READY --> QUEUED
    QUEUED --> DOWNLOADING: resource slots acquired
    DOWNLOADING --> PAUSING
    PAUSING --> PAUSED: own connections quiesced
    PAUSED --> QUEUED: resume
    DOWNLOADING --> VERIFYING: selected pieces persisted
    VERIFYING --> COMPLETED: selected files complete
    VERIFYING --> DOWNLOADING: invalid / missing pieces
    DOWNLOADING --> FAILED: unrecoverable error
    FAILED --> QUEUED: explicit retry
    CREATED --> DELETING
    WAIT_METADATA --> DELETING
    READY --> DELETING
    PAUSED --> DELETING
    DOWNLOADING --> DELETING
    COMPLETED --> DELETING
    FAILED --> DELETING
    DELETING --> DELETED
~~~

WAIT_METADATA 是逻辑任务状态；解析历史独立使用 PARSING/SUCCEEDED/FAILED。用户 Pause 不影响同 Hash 的另一个 TaskActor。

### 8.2 TaskActor

~~~java
final class TaskActor {
    private final UUID taskUuid;
    private final long userId;
    private final InfoHash infoHash;
    private final ArrayDeque<TaskEvent> mailbox = new ArrayDeque<>();
    private final PieceScheduler scheduler;
    private final TaskProgressCalculator progress;
    private TaskStatus status;
    private long revision;

    // 任意网络回调/HTTP 命令只投递事件；同任务单消费者处理状态改变
    void enqueue(TaskEvent event) { /* bounded mailbox + wakeup */ }
    void onEvent(TaskEvent event) { /* check transition; emit durable state */ }
}
~~~
调度器可由共享 Executor 驱动，但 mailbox 单任务串行执行；I/O 回调携带 taskUuid、peerSessionGeneration 和 revision，防止旧连接回调在任务重启后污染新状态。禁止由多个 HTTP 线程直接更改 TaskActor 内 BitSet。

- 锁粒度：按 taskUuid；无以 infoHash 为锁的业务临界区。
- 全局 DHT/metadata cache 只可读共享。若同 Hash 并发解析，允许共享获取中的 CompletableFuture，但任一用户取消自己解析时不可取消全局获取以影响其他等待者。
- 应用先持久化任务，然后向引擎发送 START；崩溃重启时数据库扫出非 DELETED 任务恢复，按配置决定自动续传或先暂停。

### 8.3 速率与资源隔离

计数全部按 taskUuid 收集：
- downloadedWireBytes：Peer 发来的下载 payload 字节数（包括最终未验证/重试流量，具体统计口径固定）。
- downloadSpeedBytesPerSec：该任务近期 Peer payload 采样，采用 1s 窗口或 EWMA；暂停后按采样窗衰减至 0。
- verifiedSelectedBytes：持久化且通过验证的所选有效字节；单调增长（除校验失败导致回退）。
- etaSeconds：根据剩余所选字节与平滑速度估算；速度为 0/不稳定时为 null，而非假定 0 秒。
- user quota：活动任务数、TCP 连接数、总在途 block 字节、吞吐率、磁盘配额；全局 quota 防止单用户耗尽服务。

## 9. 数据库模型与迁移设计

### 9.1 不修改 legacy 表

现有 magnet_download_task.info_hash UNIQUE 仅限制**legacy qBittorrent** 任务。新引擎使用 torrent_native_task，允许同一 info_hash 存在任意多个 taskUuid。绝不简单删除 legacy UNIQUE 就宣称获得同 hash 进度隔离。

### 9.2 MySQL 8.0 参考 DDL（实际提交迁移时完善）

~~~sql
CREATE TABLE torrent_native_task (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    task_uuid CHAR(36) NOT NULL,
    user_id BIGINT NOT NULL,
    parse_id BIGINT NOT NULL,
    info_hash VARCHAR(64) NOT NULL,
    metadata_id BIGINT NULL,
    status VARCHAR(32) NOT NULL,
    selected_files_json LONGTEXT NOT NULL,
    selected_total_bytes BIGINT NOT NULL DEFAULT 0,
    verified_selected_bytes BIGINT NOT NULL DEFAULT 0,
    downloaded_wire_bytes BIGINT NOT NULL DEFAULT 0,
    revision BIGINT NOT NULL DEFAULT 0,
    state_path VARCHAR(1024) NOT NULL,
    last_error VARCHAR(1024) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    deleted BIGINT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_torrent_native_uuid (task_uuid),
    KEY idx_torrent_native_owner (user_id, deleted, created_at),
    KEY idx_torrent_native_hash (info_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE torrent_native_file (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    task_uuid CHAR(36) NOT NULL,
    user_id BIGINT NOT NULL,
    file_index INT NOT NULL,
    relative_path VARCHAR(1024) NOT NULL,
    total_bytes BIGINT NOT NULL,
    verified_bytes BIGINT NOT NULL DEFAULT 0,
    is_selected TINYINT NOT NULL,
    UNIQUE KEY uk_native_task_file (task_uuid, file_index),
    KEY idx_native_file_owner (user_id, task_uuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE torrent_native_metadata (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    info_hash VARCHAR(64) NOT NULL,
    torrent_bencode LONGBLOB NOT NULL,
    metainfo_sha256 CHAR(64) NOT NULL,
    piece_length INT NOT NULL,
    piece_count BIGINT NOT NULL,
    total_bytes BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_native_verified_metadata (info_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
~~~

SQLite 对应脚本需要使用 INTEGER PRIMARY KEY AUTOINCREMENT、BLOB、TEXT 等合法方言；禁止盲目复制 MySQL 的 AUTO_INCREMENT、KEY、DATETIME(6)。所有新增表使用 SQL Mapper XML；不得使用注解 SQL。

**约束注意：**任务逻辑删除用 UPDATE torrent_native_task SET deleted=id WHERE id=? AND user_id=? AND deleted=0。task_uuid 为全局唯一 UUID（不允许重用）；info_hash 不得加全局 UNIQUE。无外键，保持项目惯例。相同用户、同 Hash、同文件选择也可产生多个独立任务；若产品希望提醒重复，可做应用层提示，不做强制去重。

为了并发安全，数据库更新应采用 (id,user_id,revision,deleted=0) 条件的 CAS；如果成功更新数为 0，重载并回放事件，不能默默覆盖其他工作线程的进度。

### 9.3 读取示例

~~~sql
SELECT id, task_uuid, user_id, status,
       selected_total_bytes, verified_selected_bytes
FROM torrent_native_task
WHERE task_uuid = #{taskUuid}
  AND user_id = #{currentUserId}
  AND deleted = 0;
~~~

下载历史和任务必须分页上限 30；查询关系到文件列表、ticket、删除和修改时，同样校验归属。metainfo 是全局缓存，对外 API 仍通过本人的 parseId 或 taskUuid 授权。

## 10. 新增 REST API（不破坏 legacy）

| 方法 | 路径 | 作用 | 归属校验 |
|---|---|---|---|
| POST | /api/magnet/native/parse | 解析 Magnet（复用历史 API 可选） | 从 JWT 注入 userId |
| GET | /api/magnet/native/parse/{parseId} | 元数据状态、文件列表 | parseId + userId |
| POST | /api/magnet/native/tasks | 传 parseId + selectedFileIndexes 建立独立任务 | parseId + userId |
| GET | /api/magnet/native/tasks | 当前用户任务列表 | userId |
| GET | /api/magnet/native/tasks/{taskUuid} | 任务与精确进度 | taskUuid + userId |
| GET | /api/magnet/native/tasks/{taskUuid}/files | 文件明细/完成状态 | taskUuid + userId |
| POST | /api/magnet/native/tasks/{taskUuid}/start | 开始/继续 | taskUuid + userId |
| POST | /api/magnet/native/tasks/{taskUuid}/pause | 暂停 | taskUuid + userId |
| DELETE | /api/magnet/native/tasks/{taskUuid} | 删除任务，可显式请求删除文件 | taskUuid + userId |
| POST | /api/magnet/native/tasks/{taskUuid}/files/{index}/ticket | 换短时下载票据 | taskUuid + userId + selected |
| GET | /api/magnet/native/stream/{ticket} | 支持 Range 的本地取流 | ticket 绑定归属并二次校验 |
| GET | /api/magnet/native/engine/health | 是否就绪、协议能力、受控指标 | JWT |

新 native create 请求示例：
~~~json
{
  "parseId": 123,
  "selectedFileIndexes": [0, 2]
}
~~~
返回示例（任务 UUID 不由前端决定）：
~~~json
{
  "success": true,
  "data": {
    "taskUuid": "2c3816b3-84f3-42f5-9f86-0ef5df1d4cd8",
    "engine": "NATIVE",
    "status": "QUEUED",
    "infoHash": "0000000000000000000000000000000000000000",
    "selectedTotalBytes": 1000000000,
    "verifiedSelectedBytes": 0,
    "progressPercent": 0,
    "downloadSpeedBytesPerSec": 0,
    "etaSeconds": null
  }
}
~~~
这里的 InfoHash 是**仅用于解释字段形状的占位示例**，不代表真实可用种子。

### 10.1 控制器伪代码

~~~java
@PostMapping("/native/tasks")
public ApiResponse<CreateTaskResponse> create(
    HttpServletRequest request, @Valid @RequestBody CreateTaskRequest body
) {
    Long userId = currentUserId(request);
    var parsed = historyRepository.requireOwned(userId, body.parseId());
    var task = nativeTorrentService.create(userId, parsed, body.selectedFileIndexes());
    return ApiResponse.ok(task);
}

@PostMapping("/native/tasks/{taskUuid}/pause")
public ApiResponse<Void> pause(HttpServletRequest req, @PathVariable UUID taskUuid) {
    Long userId = currentUserId(req);
    nativeTorrentService.requireOwned(userId, taskUuid);
    nativeTorrentService.pause(userId, taskUuid);
    return ApiResponse.ok(null);
}
~~~

Controller 不持有 peer socket、不会阻塞等待几分钟的元数据获取，解析和下载异步执行。与现有 Spring Boot API 响应体保持 ApiResponse{success,data,error}。

## 11. 进度推送与前端

沿用 frontend/src/components/MagnetDownloader.vue、src/api/magnet.js 的当前风格；以任务 UUID 渲染每张任务卡。
- create 后立即显示独立状态。
- GET /api/magnet/native/tasks 每秒轮询，组件卸载清理定时器；页面不可见时降低频率；状态 COMPLETE/PAUSED 时降低频率。
- 若将来接 WebSocket，也必须以登录用户订阅 taskUuid，校验订阅所有权，不可公开 topic by infoHash。
- UI 显示“已选大小、已验证已选字节、实时网络下载速度、剩余时间、当前状态、文件列表”。
- 解析历史是按用户的记录；一个解析历史可以创建 N 个任务；一个资源 InfoHash 不能充当下载任务唯一标识。
- legacy QBT/NATIVE 标明 engine 类型；复用现有主题，不替换为 React/Ant Design。

## 12. 配置（backend/.env + application.yml）

~~~properties
# 仅为设计示例，接入时统一接入现有 .env / application.yml 绑定
TORRENT_ENGINE_MODE=QBT
TORRENT_NATIVE_ENABLED=false
TORRENT_NATIVE_STORAGE_ROOT=D:/clipfetch-data/torrent

TORRENT_NATIVE_MAX_ACTIVE_TASKS_PER_USER=3
TORRENT_NATIVE_MAX_PEERS_PER_TASK=20
TORRENT_NATIVE_MAX_PEERS_PER_USER=40
TORRENT_NATIVE_MAX_PEERS_GLOBAL=400
TORRENT_NATIVE_MAX_METADATA_BYTES=8388608
TORRENT_NATIVE_MAX_MAGNET_LENGTH=16384
TORRENT_NATIVE_MAX_INFLIGHT_BYTES_PER_TASK=4194304
TORRENT_NATIVE_CONNECT_TIMEOUT_MS=8000
TORRENT_NATIVE_PEER_READ_TIMEOUT_MS=30000
TORRENT_NATIVE_ALLOW_PRIVATE_NETWORK=false
TORRENT_NATIVE_AUTO_RESUME_ON_RESTART=false
TORRENT_NATIVE_DHT_ENABLED=true
TORRENT_NATIVE_TCP_LISTEN_ENABLED=false
~~~

Linux 示例存储根 /var/lib/clipfetch/torrent；在实际部署时目录必须对 Spring Boot 运行账户可读写。以上配额是**保守设计起点**而不是性能标准，须根据 CPU、带宽、磁盘、开放文件数压测调优。DHT UDP 绑定端口与监听策略也应写入配置；首期只有出站连接时可下载，但入站可达性受到限制。

没有独立 BT 服务器是必须条件，因引擎与 Spring Boot 单体进程一同运行；如果未来确实需要跨机 BT 节点，要另行评审跨进程任务归属与状态复制，不应以“切换 IP”简单实现。

## 13. 线程、内存与故障隔离

- 网络层：固定数量 Selector reactor，读写全部非阻塞，连接数与 ByteBuffer 池全局限额。
- CPU 层：有界 Piece 哈希线程池，禁止 Socket 线程做大文件 SHA-1。
- 磁盘层：有界 File I/O 工作池，单 TaskActor 按 Piece commit 序列化提交 checkpoint。
- 控制层：HTTP 请求只提交命令并读取快照，避免 REST 工作线程被 Peer I/O 阻塞。
- 全局 OutOfMemory / FD 耗尽之前应拒绝创建新任务或新连接，业务错误面向用户。
- 限制单任务元数据大小、文件数量、Piece 数、在途内存、任务数、Tracker 响应长度、DHT 请求速率。
- Peer 层非法帧断开该连接，不影响其他用户 TaskActor；文件或 checkpoint 故障只标记关联任务 FAILED/PAUSED。

## 14. 安全审查清单

1. **对象级访问控制**：每个 /native/tasks/{uuid} 与文件端点按 userId 验证，拒绝 IDOR。
2. **SSRF 和网络越权**：所有 tracker URL、DNS 结果、跳转、Peer/IP/端口必须限制 loopback、link-local、private、metadata endpoint、保留网段；测试环境显式配置例外。
3. **恶意 Torrent**：禁止路径穿越、符号链接、NUL/非法文件名、大小溢出、超大文件数和 Unicode 名称冲突。
4. **用户存储配额**：防止恶意任务耗尽整个磁盘；预估 selectedTotal 和边界缓存的开销，写入时动态校验。
5. **流式下载票据**：随机强 token、短 TTL、关联 userId/taskUuid/fileIndex，禁止 URL 放 JWT，严控 Range 边界和响应头 filename。
6. **隐私**：禁止将用户 token、文件路径、磁力完整 URL 等敏感信息打到公共日志；共享发现缓存不提供跨用户行为分析。
7. **数据完整性**：Piece hash 失败不更新进度；停止/删除后旧事件因 generation 不同必须丢弃。
8. **合规**：仅处理用户拥有或授权下载的内容；不提供搜索侵权资源或绕过授权的能力。
9. **部署安全**：私有端口不对公网随意开放；如开启入站 TCP/DHT UDP 监听，需显式网络策略审查。

## 15. 测试实现方案

### 15.1 单元与协议契约
- Magnet btih hex/Base32/重复 tr/非法参数/仅 v2 输入。
- Bencode 与原始 info bytes hash 的 golden vectors；长度溢出、递归深度攻击。
- Tracker compact peers、UDP transaction ID、超时重试、私网地址拦截。
- DHT XOR 距离/k-bucket/KRPC 编解码/节点超时。
- Peer Wire：分帧、半包、乱序、choke、bitfield、block 越界。
- BEP9：16 KiB metadata 分片缺失、重复、错误哈希、超大元数据。
- Piece 跨文件区间、选择文件进度数学、SHA-1 失败重下。

### 15.2 本地端到端（不依赖公网）
- 创建自有授权测试数据：单文件、大多文件、同名冲突、跨 piece 边界、零长度文件。
- 测试 Peer/Tracker fixture 由测试代码自建 TCP/UDP 通道与响应脚本，不调用外部下载内核。
- 同 Hash 由 A、B 各自下载不同所选文件；模拟 A pause/delete/crash，确认 B 继续。
- 恢复测试：文件写入中异常退出、checkpoint 更新前退出、磁盘内容损坏后退出、边界缓存丢失。
- 性能测试：限制总 Peer=400 的情况下观察 FD、堆外内存、磁盘写延迟、API p95；阈值依据机器压测结果确定。

### 15.3 DB/API 回归
- SQLite/MySQL 双库 migrate 清库与历史库升级；已发布 V1～V12 checksum 不变化。
- QBT legacy 任务正常创建、删除、文件取流；native 不误操作 legacy。
- 权限矩阵：A 看 B 的列表/UUID/fileIndex/ticket，均拒绝；相同 InfoHash 不构成授权。
- 同一个用户的重复同 Hash native 任务与不同文件选择均可以存在。

## 16. 发布、迁移、回滚

1. NATIVE_ENABLED=false 上线数据库结构和只读代码，确保旧业务零回归。
2. 灰度用户新任务显式选择 native；原有 QBT 任务留在 legacy 表，不尝试反向读取 native checkpoint。
3. 观测：任务创建成功率、Metadata 校验通过率、Piece hash mismatch、断点恢复成功率、隔离测试回归、磁盘与堆外资源。
4. 回滚仅影响**新任务创建路由**；native 已下载文件不能删，已存在 native 任务转维护/暂停，允许用户获取已完成文件。
5. 待同功能覆盖与长期稳定性验证完成后再评估默认路由切换。**本方案未实际修改现有下载代码、数据库或服务状态。**

## 17. 实施顺序与提交边界

建议按 P0-P6 小步提交、每阶段编译与测试。优先：
1. 抽象 TorrentEngine API + 配置与 tests；
2. 自研 Magnet/Bencode/Metainfo；
3. Tracker+DHT+ut_metadata 完成合法 Magnet 文件树；
4. TCP Peer+Piece+Checkpoint；
5. taskUuid/userId 数据与接口隔离；
6. 前端与 ticket/Range；
7. 受控并发/故障/兼容性验证；
8. 灰度上线。

单次改动不要同时重构旧 qBittorrent、Web API、UI 和数据库历史迁移；开发代理应先读 AGENTS.md 与本规划书。

## 18. 官方 BEP 规范

- BEP3 核心协议：https://www.bittorrent.org/beps/bep_0003.html
- BEP5 DHT：https://www.bittorrent.org/beps/bep_0005.html
- BEP9 元数据：https://www.bittorrent.org/beps/bep_0009.html
- BEP10 扩展协议：https://www.bittorrent.org/beps/bep_0010.html
- BEP11 PEX：https://www.bittorrent.org/beps/bep_0011.html
- BEP15 UDP Tracker：https://www.bittorrent.org/beps/bep_0015.html
- BEP29 uTP：https://www.bittorrent.org/beps/bep_0029.html
- BEP52 BitTorrent v2：https://www.bittorrent.org/beps/bep_0052.html

说明：协议规范的具体字段含义、版本状态必须以官方 BEP 原文为准，本设计为项目实现蓝图。
