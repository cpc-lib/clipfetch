# ClipFetch · P2 DHT 迭代 Peer 发现阶段交接

> 日期 2026-10-14；项目 D:\release\clipfetch。
> 本轮执行 mvn -f D:\release\clipfetch\backend\pom.xml -q clean test，exit code 0。
> 已实现有界迭代 DHT Peer 发现；尚未完成真实公网 DHT Bootstrap、TCP Peer、BEP9 ut_metadata 或实际文件下载。

## 1. 本轮代码
- backend/src/main/java/cc/ivera/magnet/engine/dht/IterativePeerLookup.java
  - 按 Node ID 与目标 InfoHash 的 XOR 距离选择最近未访问节点。
  - 复用 DhtQueryClient 的单节点 get_peers，将返回 nodes 加入候选，将 values 加入 Peer 集合。
  - 按 IP:port 去重，限制 maxQueries、maxPeers、maxCandidates、总耗时和单次超时。
  - 网络故障节点跳过，保留已发现结果。默认使用 TrackerAddressPolicy 过滤回环、私网等地址。
  - 提供可注入 QueryTransport 接口便于离线测试。
- backend/src/test/java/cc/ivera/magnet/engine/dht/IterativePeerLookupTest.java
  - 验证循环节点不会重复查询；测试查询数、Peer 数预算；测试默认拒绝回环节点。

## 2. 验证
命令：mvn -f D:\release\clipfetch\backend\pom.xml -q clean test
结果：进程退出码 0，新增测试通过。
保持旧 qBittorrent 实现不变，原生引擎默认关闭；未创建 Git commit。

## 3. 尚未完成
- 当前采用串行查询；尚无 alpha=3 并发 Kademlia 迭代调度。
- 由调用方提供 Bootstrap 节点；尚无独立的 Bootstrap 池、k-bucket 活性、节点替换。
- 现有地址策略属于初版，后续需补齐保留 IPv4/IPv6 和地址安全审计。
- 尚未实现统一 Tracker+DHT Peer 候选聚合服务、TCP Peer 握手、BEP10/9 元数据下载与真实文件列表端到端验证。
- 尚未实现 Piece 下载/存储、断点续传、用户 taskUuid 级下载生命周期和进度；不能称作完整原生下载引擎。

## 4. 下一轮
1. 合并 HTTP/UDP Tracker 和 DHT 候选，建立按 IP:port 去重、上限控制与来源标记。
2. 实现 TCP PeerConnector：连接超时、BEP3 握手、InfoHash 校验、Peer ID、半包与异常关闭。
3. 继续实现 BEP10 Extended Handshake、BEP9 ut_metadata 分片和原始 InfoHash 完整验证。
4. 对合法受控 Magnet 开展本地 Peer fixture 联通测试，再推进真实元数据文件列表验收。
5. 后续 P3/P4 逐步实现 Piece 下载/持久化与独立用户下载状态、暂停恢复及进度。

## 5. 约束
先读 AGENTS.md 以及此前交接。保持 TORRENT_NATIVE_ENABLED=false；不要覆盖未提交修改。始终使用已认证 userId+taskUuid 控制 Native 任务，不能依据 infoHash 操作用户下载任务。
