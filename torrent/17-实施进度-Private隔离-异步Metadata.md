# ClipFetch 自研 BitTorrent · Private Swarm 隔离与异步 Metadata 执行器交接

> 日期：2026-10-14；项目根目录 D:\release\clipfetch
> 验证命令：mvn -f D:\release\clipfetch\backend\pom.xml -q clean test
> 最终：Exit Code 0，37 个测试类，83 项测试，Failures=0，Errors=0，Skipped=0。
> 生产状态：原生引擎未启用（TORRENT_NATIVE_ENABLED=false），旧 qBittorrent 链路保持不变。

## 1. 本轮改造的安全问题

上一轮加速接口通过 Options.verifiedPublicSwarm 的普通布尔值来决定是否允许公共 Tracker / PeX / LSD；布尔值由任意上层代码设置，并不能证明一个 InfoHash 对应的是公开种子。

已添加元数据凭证校验：不能仅凭客户端声称 public 就对公开 Tracker、PeX 和 LSD 执行额外发现。

## 2. 新增与修改代码

### VerifiedSwarmPrivacy：认证元数据和私有 Tracker 白名单

新增 backend/src/main/java/cc/ivera/magnet/engine/metainfo/VerifiedSwarmPrivacy.java：
- 通过 TorrentV1MetainfoParser 验证完整 v1 .torrent 与预期 InfoHash 一致；
- 检查原始 info 字典的 private 字段：缺省或 0 为 public，1 为 private，其他值及错误类型明确拒绝；
- 对公开种子签发不可由外部代码直接构造、绑定到目标 InfoHash 的 PublicPermit；
- 对私人种子禁止签发 PublicPermit；
- 只从 hash 已校验的完整 .torrent 根字典解析 announce/announce-list，形成 private tracker URL 精确白名单；
- 仅有 BEP9 原始 info 字典时，可以识别 private，但无法认证 Tracker 地址，因此私人种子的允许 Tracker 列表为空（fail-closed）；
- 对元数据和 Magnet InfoHash 不匹配情况，查询网络之前立即拒绝。

修改：
- engine/discovery/PeerDiscoveryAccelerator.java：enrich 新增 PublicPermit 参数，并校验目标 InfoHash 一致。Options 布尔值只是业务启用偏好，不再构成身份/隐私凭证。
- engine/discovery/DiscoveryService.java：discoverAccelerated 同样要求 PublicPermit 且在任何 Tracker/DHT 请求前验证 hash；新增 discoverVerified。已知 private 时禁止 DHT，且磁力链接上的 tr= 地址必须出现在可信完整 .torrent 的 private Tracker 白名单，否则跳过。
- engine/discovery/MetadataResolver.java：resolvePublicAccelerated 同样要求 PublicPermit；新增 resolveVerified 走经确认的 private/public 发现模式。

**边界提醒**：单独 Magnet 链接天然不包含 private=1 标志，因而无法单凭 Magnet 判断隐私；现有普通 discover() 仍支持公开 Magnet 的 DHT Bootstrap。今后的生产任务入口必须在识别到可信 private 元数据时强制使用 discoverVerified，未知隐私时需明确网络披露策略和用户授权。不得把本轮理解为“已从纯 Magnet 完全自动识别所有 private 种子”。

### NativeMetadataJobManager：独立、可取消的元数据任务原型

新增 backend/src/main/java/cc/ivera/magnet/engine/discovery/NativeMetadataJobManager.java：
- 独立有界 ThreadPoolExecutor（可配置并发线程、等待队列与单用户最多待完成任务数）；
- submit 返回 UUID（仅内存中的 metadata jobId，不是正式业务下载 taskUuid），调用者传递经认证的 userId；
- status / cancel / await 强制按 userId + jobId 判定归属，不返回其他用户的任务；
- 状态：QUEUED / RUNNING / SUCCEEDED / FAILED / CANCELLED / TIMED_OUT；
- 结果仅暴露已成功完成的 InfoHash、名称、文件数量和总字节数，不伪造 Piece 下载进度；
- await 按用户等待预算执行，status/await/submit 按需执行总时限检查、Future.cancel(true) 和懒清理；
- 没有新增 @Scheduled、MQ、Redis、微服务或下载数据库表。

**原型限制**：
- 生命周期仍在 JVM 内存，服务重启后丢失；没有正式 authenticated Web API、数据库/持久状态和前端接入；
- 超时判断采用轮询/按需，未设置后台定时调度器；网络中的 DNS 和阻塞 Socket 写入不保证在 Future.cancel(true) 时立即中断；
- 到达截止时间会停止对用户报告等待中的结果，但底层网络任务可能需要等待既有 Socket 超时才能完全回收工作线程；
- 并发队列限额和非下载型作业状态已落地，但尚非可生产使用的 Native 多用户下载状态机。

## 3. 测试

新增：
- engine/metainfo/VerifiedSwarmPrivacyTest.java：校验 public/ private、Hash 匹配、异常 private 字段、BEP9 raw info。
- engine/discovery/PrivateSwarmDiscoveryTest.java：已知 private 不执行 DHT、仅允许完整 .torrent 授权 Tracker、BEP9 raw info 私有种子禁止未授权 Tracker、错误 Hash 在查询任何网络前拒绝。
- engine/discovery/NativeMetadataJobManagerTest.java：异步成功结果、userId 隔离、跨用户取消拒绝、并发队列限制、取消任务、总超时不回报虚假成功。
调整原有 PeerDiscoveryAcceleratorTest / AcceleratedMetadataResolverTest 使用完整校验后的 PublicPermit。

真实元数据抽样验证（只读）：
D:\TEMP\clipfetch-ubuntu-26.04-desktop-amd64.iso.torrent
已由 Java 21 JShell 使用实际 VerifiedSwarmPrivacy.verifyTorrent 验证：
- InfoHash = dafc8c076ca2f3ed376eeae7c76a0d6be2415c45；
- privateTorrent = false；
- PublicPermit 对应相同 InfoHash；
- 未下载 Ubuntu ISO 内容。

## 4. 下一轮研发重点

1. 让 Native Metadata Job 接入 Spring 的独立请求处理层，必须验证登录 userId，禁止被攻击者通过前端伪造 userId；限定任务数量/工作线程和全链路外部请求预算，支持取消。
2. 生产级隐私：对外公开的“增强发现”入口必须只接受服务端从经验证 .torrent 派生的 PublicPermit；禁止客户端提交伪造 public 状态。未知 private 的纯 Magnet 应明确隐私策略。
3. 修复 DHT/Tracker DNS 超时与阻塞 Socket 取消的剩余风险，对全解析流程提供可强制截止的网络 IO 方案。
4. 后续增强 PeX 长连接与 delta dropped、Tracker 响应健康评分和 LSD 多网卡局域网实测；默认不广播用户所有种子。
5. P3 PieceScheduler、持久化 verified-piece checkpoint、异常恢复和选中文件边界处理；P4 才开展真实用户下载、速度/进度/暂停/恢复/删除任务管理。

## 5. 接手约束

- 先读项目根目录 AGENTS.md、torrent/01~17 交接文件；
- 保留 TORRENT_NATIVE_ENABLED=false，现有 qBittorrent 生产下载功能不变；
- 保留已有 Git 工作区未提交修改，不主动创建 Git commit；
- 不修改历史 Flyway、不引入 Redis/MQ 或定时任务。
