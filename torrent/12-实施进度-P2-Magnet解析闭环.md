# ClipFetch 自研 BitTorrent · P2 Magnet 解析闭环交接

> 日期：2026-10-14  
> 项目根目录：D:\release\clipfetch  
> 当前状态：**受控本地环境下实现了 Magnet -> HTTP Tracker -> TCP Peer -> BEP10/BEP9 -> InfoHash 校验 -> 文件列表**。  
> 仍未完成真实公网互操作、安全审计、P3 Piece 下载、P4 多用户实际任务与进度管理。

## 1. 本轮实际代码

- backend/src/main/java/cc/ivera/magnet/engine/discovery/DiscoveryService.java
  - 使用原生 MagnetUriParser 读取 btih 与 Tracker；
  - 汇总 HTTP/UDP Tracker、DHT 的候选 Peer（默认网络适配器复用现有原生客户端，不调用 qBittorrent）；
  - 复用 PeerCandidatePool 进行 IP:port 去重、来源标记、有限容量和地址策略过滤；
  - 限制最多查询的 Tracker 数；Tracker 失败时继续检查其他来源，DHT 失败不丢弃已得到的 Tracker 结果；
  - 默认 DHT 查询需调用方显式提供 Bootstrap 节点，否则只搜索 Magnet 提供的 Tracker；
  - 对测试提供 TrackerAnnouncer、DhtPeerFinder 可注入接口。
- backend/src/main/java/cc/ivera/magnet/engine/discovery/MetadataResolver.java
  - 对 DiscoveryService 返回的 Peer 候选逐一执行 PeerMetadataSession；
  - 失败或获得非法元数据时，有限次数尝试下一个 Peer；
  - 保留原始 info bencode 字节封装成合规 Torrent 根字典；
  - 通过 TorrentV1MetainfoParser 再次校验预期 InfoHash、解析 v1 文件名与文件大小；
  - 返回 TorrentInfo、发现 Peer 数量与实际尝试次数；
  - 提供可注入 PeerMetadataFetcher 用于确定性的离线故障测试。
- 当前都是 Java 单体内的独立组件，不新增依赖、微服务或 RabbitMQ/Redis，也未接入真实业务下载状态。

## 2. 新增测试与实测结果

命令：
~~~powershell
mvn -f D:\release\clipfetch\backend\pom.xml -q clean test
~~~
最终结果：**Exit Code 0**。本轮新增 3 个测试类共 6 项测试，0 failure、0 error、0 skipped。

- DiscoveryServiceTest：3 项。Tracker/DHT 来源合并去重、Tracker 失败后继续、默认私网 Peer 过滤。
- MetadataResolverTest：2 项。首个 Peer 失败后的回退、InfoHash 与文件树一致性、错误元数据拒绝、Peer 尝试次数限制。
- NativeMagnetIntegrationTest：1 项。使用本地 ServerSocket 分别模拟 HTTP Tracker 与 TCP Peer，真实使用网络 Socket 完成：
  Magnet v1 -> HTTP announce -> compact peer -> BEP3 TCP Handshake -> BEP10 extension handshake -> BEP9 metadata request/reply -> SHA-1 InfoHash validation -> Torrent v1 文件列表。
  **这个测试完全使用本地合成 fixture，不依赖公网，也不调用 qBittorrent。**

对应源码：
- backend/src/test/java/cc/ivera/magnet/engine/discovery/DiscoveryServiceTest.java
- backend/src/test/java/cc/ivera/magnet/engine/discovery/MetadataResolverTest.java
- backend/src/test/java/cc/ivera/magnet/engine/discovery/NativeMagnetIntegrationTest.java

## 3. 未完成和当前限制

- 网络默认适配器当前仅支持明文 HTTP 和 UDP Tracker；HTTPS Tracker 尚未实现安全 TLS/SNI/证书检查与 IP 固定，HTTP chunked 也未支持。
- 真实公网 DHT Bootstrap、Kademlia k-bucket 维护、alpha 并发查询、PEX、IPv6 仍未完善。
- MetadataResolver 和 PeerMetadataSession 当前是同步有限尝试；真实生产还需绝对总截止时间、取消、并发池与重试策略，以及流量公平配额。
- TrackerAddressPolicy 仍须补齐地址空间分类、DNS 重绑定风险、IPv4 映射 IPv6、安全出站代理等审计；禁止将测试专用的 allowPrivateForTests=true 设为生产默认。
- 当前解析闭环没有开放新的完整 Magnet 元数据查询 API。既有 /api/magnet/native/magnet/inspect 只是离线 btih 格式检查，不会触发本流程；避免同步网络请求直接阻塞 Web 线程。
- 尚未保存原生元数据历史、创建 Native 用户下载任务、下载 Piece、验证 Piece SHA-1、落盘、恢复、暂停、取消或跟踪每用户下载百分比。
- 当前没有公网真实种子兼容性证明，不能声称自研 BitTorrent 完成或能下载。
- 原生功能开关 TORRENT_NATIVE_ENABLED 默认 false；旧 qBittorrent 主业务继续运行。

## 4. 下一轮优先级

1. 建立有总截止时间、取消、统一 peer/tracker 查询预算的 NativeMagnetParseService；不要直接在 HTTP 请求线程串行等待全链路。
2. 将只读已验证的 Metainfo 与 parse history 持久化，严格按已认证 userId 关联；检查多用户同 InfoHash 的隔离和失败恢复。
3. 在授权测试资源上执行真实公网 Tracker+DHT+Peer Metadata 兼容验证，并处理其差异。
4. 准备进入 P3：PieceScheduler + PeerSession + FileChannel 真实下载与 SHA-1 Piece 校验。
5. P4 才启用任务创建与 userId+taskUuid 独立下载会话、目录、状态、速度/百分比、暂停恢复、删除。

## 5. 接手约束

先阅读根 AGENTS.md，以及 torrent/01~12 阶段文档。检查 git status，不能覆盖用户原有的未提交改动。不主动提交 Git，也不引入与项目规则冲突的新框架。任何生产可见的 Native 下载能力必须在真实完整实现并验收后再打开开关。
