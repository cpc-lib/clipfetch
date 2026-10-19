# ClipFetch 自研 BT 引擎 · P2 Peer TCP 握手与候选池

> 日期：2026-10-14。项目：D:\release\clipfetch。
> 状态：已实现候选池与一次性 TCP 握手；未实现完整 Peer 会话、扩展消息、Metadata 或文件下载。

## 代码交付

- backend/src/main/java/cc/ivera/magnet/engine/peer/PeerCandidatePool.java
  - HTTP_TRACKER / UDP_TRACKER / DHT 来源标识；
  - 同 IP:port 去重、来源合并、限制候选池容量；
  - 使用已有 TrackerAddressPolicy 过滤受限地址；只收集候选，不建立连接。
- backend/src/main/java/cc/ivera/magnet/engine/peer/PeerConnector.java
  - 使用固定解析 IP 建立有超时的 TCP 连接；
  - 根据 BEP3 构造和发送握手，声明 BEP10 扩展能力标记；
  - 读取并解析完整响应，校验 InfoHash、Peer ID；
  - 无论成功失败都自动关闭 Socket，不保留连接，不执行任何下载。
- 测试：PeerCandidatePoolTest.java、PeerConnectorTest.java。
  - 测试不同来源去重、容量限制、默认拒绝 loopback；
  - 使用本机 ServerSocket 模拟 Peer，验证正确 InfoHash 及不匹配 InfoHash 的处理。

## 验证

运行：mvn -f D:\release\clipfetch\backend\pom.xml -q clean test
结果：Exit Code 0。

## 未完成及风险

1. PeerConnector 仅完成一次性同步握手；尚未实现 SocketChannel/Selector、长期 Peer 会话、读写消息、心跳、限速、连接池、取消操作。
2. 仍未实现 BEP10 Extended Handshake、BEP9 ut_metadata 元数据分片获取及 InfoHash 完整校验。
3. Tracker / DHT 候选池尚未接入正式 DiscoveryService 编排；暂无原生下载任务创建/进度控制业务闭环。
4. TrackerAddressPolicy 仍须进一步审查公网特殊地址、DNS、IPv6、实际连接安全性。
5. 仅本机模拟测试通过；没有公网 Peer 互操作和真实文件列表端到端测试。
6. 原生引擎 TORRENT_NATIVE_ENABLED 保持默认 false；现有 qBittorrent 下载业务不变，没有创建 Git commit。

## 下一轮优先级

- 基于保持连接的 Peer Session 实现 BEP10 扩展握手；
- 实现 BEP9 ut_metadata 分片请求和校验；
- 合并 HTTP/UDP Tracker + DHT 候选并端到端获取受控种子的真实文件清单；
- 后续 P3/P4 再实现 Piece 下载、持久化、用户 taskUuid 隔离的进度/暂停/恢复/删除。
