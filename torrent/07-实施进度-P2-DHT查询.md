# ClipFetch 自研 BitTorrent · P2 DHT 查询交接

> 日期：2026-10-14 · 测试：mvn -f D:\release\clipfetch\backend\pom.xml -q clean test，exit code 0。
> 本轮交付：**单个 DHT 节点的 find_node/get_peers UDP 查询和有效回复解析**，尚未实现 DHT 迭代查找、Peer TCP 与真实元数据获取。

## 1. 本轮代码

- backend/src/main/java/cc/ivera/magnet/engine/dht/DhtLookupResponse.java：验证 KRPC r 回复 ID；解析 IPv4 compact nodes（26 字节/节点）和 values（6 字节/Peer），读取可选 token，限制长度，结果防御性复制。
- backend/src/main/java/cc/ivera/magnet/engine/dht/DhtQueryClient.java：以已有 KrpcCodec 编码 find_node/get_peers，进行出站 UDP 查询，使用 TrackerAddressPolicy 验证端点与回复地址，匹配 transaction ID，限定超时，解析目标节点回复。
- backend/src/test/java/cc/ivera/magnet/engine/dht/DhtQueryClientTest.java：本地 DatagramSocket 模拟 get_peers 的完整收发，返回 nodes/values/token，验证结果和无效 nodes 长度被拒绝。
- 既有 qBittorrent 下载通道完全保留；未新增 native 下载任务创建或管理 API。

## 2. 通过测试

执行命令：
~~~powershell
mvn -f D:\release\clipfetch\backend\pom.xml -q clean test
~~~
结果：退出码 0。测试为本地模拟 DHT 节点，不代表公网 DHT 互操作已通过。

## 3. 能力边界

- 已有 KRPC 编解码、DHT 路由表、DHT UDP Ping 和单节点 find_node/get_peers 查询。
- **还没有** bootstrap 节点维护、k-bucket 活性/淘汰策略、并行迭代查询、重试/退避、Peer 候选去重与资源限额。
- 仅支持 IPv4 compact nodes/values，不支持 nodes6 等 IPv6 字段。
- 未接入 HTTP/UDP Tracker 与 DHT 查询结果的统一 Peer 发现服务。
- 未实现 Peer Wire TCP、BEP10 扩展握手、BEP9 ut_metadata 的分片收集，故还不能独立从 Magnet 得到完整文件列表。
- 未实现 Piece 下载、文件落盘、断点与真实用户级任务状态；不得将本轮能力描述为可用的原生下载引擎。

## 4. 下一阶段

1. 构建带限额、时间预算和去重的 DHT IterativePeerLookup，并为多个受控节点构建循环/无效回复集成测试。
2. 完善 KRPC error 类型与 token、节点查找流程，DHT k-bucket 活性检测。
3. 汇总 DHT 与 Tracker 提供的 Peer 候选，过滤不安全地址。
4. 实现 TCP Peer 握手和 BEP10/BEP9 元数据获取，验证元数据与 InfoHash 一致。
5. 完成合法测试种子文件树端到端验收后进入 P3 Piece 下载与 P4 多用户隔离。

## 5. 用户隔离与安全要求

- 继续保持 TORRENT_NATIVE_ENABLED=false。
- DHT 网络基础设施可共享，但业务任务永远以 userId + taskUuid 身份隔离；不能依据 infoHash 删除/暂停任务。
- 测试时 TrackerAddressPolicy(true) 仅限 loopback fixture；生产默认禁止回环/内网目标，并须完善 IPv4/IPv6 地址分类。
- 不创建 Git commit，不修改历史数据库迁移。
