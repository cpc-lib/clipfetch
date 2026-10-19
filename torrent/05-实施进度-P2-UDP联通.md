# ClipFetch 自研 BitTorrent · P2 UDP 网络联通交接

> 日期：2026-10-14 · 本次已执行 Maven clean test，退出码 0。
> 状态：**完成 UDP Tracker Connect/Announce 与 DHT Ping 的基础网络收发和本地模拟测试**；仍未实现 DHT get_peers、HTTP Tracker 真实客户端、TCP Peer、ut_metadata 或实际下载。

## 1. 实际变更

- backend/src/main/java/cc/ivera/magnet/engine/tracker/TrackerAddressPolicy.java：默认禁止回环/私网/链路本地/多播等目标；本地模拟测试允许显式测试模式。仅为第一版网络策略，后续公网部署还需补全特殊 IPv4/IPv6、DNS 重绑定、代理/重定向等安全策略。
- backend/src/main/java/cc/ivera/magnet/engine/tracker/UdpTrackerClient.java：DatagramChannel 出站连接，UDP connect -> announce、事务 ID 过滤、超时重试、Peer 列表解析。
- backend/src/main/java/cc/ivera/magnet/engine/dht/DhtUdpClient.java：KRPC Ping UDP 网络请求，回复事务匹配，校验节点 ID，超时处理。
- backend/src/test/java/cc/ivera/magnet/engine/tracker/UdpTrackerClientTest.java：使用本地 DatagramSocket fixture 模拟 connect/announce 回复；验证默认禁止 loopback。
- backend/src/test/java/cc/ivera/magnet/engine/dht/DhtUdpClientTest.java：本地 UDP fixture 发送 KRPC ping 回复，并核对事务 ID 与节点 ID。
- 上轮实现的 TrackerProtocol、KrpcCodec 和 DhtRoutingTable 不改动协议行为。
- 旧 qBittorrent 下载逻辑及 legacy 数据库没有修改。Native 引擎仍默认关闭。

## 2. 已验证

执行命令：
~~~powershell
mvn -f D:\release\clipfetch\backend\pom.xml -q clean test
~~~
返回 exit code 0。上述 UDP loopback fixture 随 Maven 测试执行成功。

## 3. 尚未达到的里程碑

- UDP Tracker 尚需更完善的重试/错误响应、IPv6、响应地址/端口过滤和取消机制。
- DHT Ping 不是 DHT Peer 发现：还缺 Kademlia 节点活性、KRPC find_node/get_peers/response nodes、迭代查找与 token 处理。
- HTTP(S) Tracker 网络客户端尚未开发。
- Peer TCP / BEP10 / BEP9 下载 Metadata 尚未开发。
- Piece 的下载、落盘、断点恢复、同 Hash 多用户独立真实下载仍未开发；不能将 P2 当成完整下载引擎。

## 4. 下一轮优先顺序

1. 对 TrackerAddressPolicy 增加全面的特殊地址测试和安全审计；避免回环/DNS/保留地址绕过。
2. 实现 HTTP TrackerClient：安全域名解析、固定目标地址、response 限长、不能跟随不可信跳转；本地 HTTP fixture 测试。
3. UDP TrackerClient 处理 BEP15 error action、丢包、重传，完善响应 Peer IP 安全过滤。
4. 扩展 KRPC r 响应 nodes/values 和 find_node/get_peers；真正实现 DHT 迭代节点发现。
5. 实现 NIO PeerConnector、BEP10 扩展握手和 BEP9 ut_metadata，重建原始 info 字节并按 InfoHash SHA-1 校验。
6. P2 验收应独立获取合法测试磁力的文件目录；P3/P4 才真正进入 Piece 下载与用户任务进度隔离。

## 5. 安全/兼容提示

- 调试本地允许私网的 TrackerAddressPolicy(true) 只能供 fixture 使用；绝不把它当生产默认值。
- 每用户进度按 taskUuid 单独管理，DHT 路由表可以共享；绝不能基于 infoHash 操作下载任务。
- 没有增加任何可见原生下载任务按钮或假进度。默认 TORRENT_NATIVE_ENABLED=false。
- 未创建 Git commit。
