# ClipFetch 自研 BT · Ubuntu 26.04 真实 Magnet Metadata 实测

> 日期：2026-10-14  
> 项目：D:\release\clipfetch  
> 状态：已核验官方 .torrent 原始 Metadata 与用户 Magnet InfoHash 完全一致；**纯 Magnet 的 DHT→BEP9 Metadata 仍未实测成功**。  
> 实测只下载约 486 KiB 的官方 .torrent 元数据文件，未下载 6.5 GB ISO 文件，也未开始 Piece 文件下载。

## 1. 用户原始 Magnet

~~~text
magnet:?xt=urn:btih:dafc8c076ca2f3ed376eeae7c76a0d6be2415c45&dn=ubuntu-26.04-desktop-amd64.iso&xl=6518974464
~~~

解析输入：
- v1 InfoHash：dafc8c076ca2f3ed376eeae7c76a0d6be2415c45
- dn：ubuntu-26.04-desktop-amd64.iso
- xl：6518974464 字节
- **没有 tr= Tracker 地址**。仅从 Magnet URI 字符串本身不能得到完整 .torrent 的 pieces 等元数据。

## 2. Ubuntu 官方 .torrent 获取与本地解析（成功）

权威元数据 URL：
https://releases.ubuntu.com/26.04/ubuntu-26.04-desktop-amd64.iso.torrent

Windows 远程设备直接执行 HTTPS 下载：
- 保存路径：D:\TEMP\clipfetch-ubuntu-26.04-desktop-amd64.iso.torrent
- 下载大小：497716 字节
- 使用项目实际代码 cc.ivera.magnet.engine.metainfo.TorrentV1MetainfoParser.parse(...) 解析该文件。

实测解析结果：
| 字段 | 结果 |
|---|---|
| InfoHash | dafc8c076ca2f3ed376eeae7c76a0d6be2415c45 |
| 文件名 | ubuntu-26.04-desktop-amd64.iso |
| 文件数 | 1 |
| 总字节 | 6518974464 |
| Piece Length | 262144（256 KiB） |
| Piece Count | 24868 |
| 与原 Magnet hash 匹配 | 是 |
| 与原 Magnet xl 匹配 | 是 |

此路径**已经得到完整且 SHA-1 校验匹配的 torrent metainfo**，但其来源是 Ubuntu 官方 .torrent URL，不是从纯 Magnet DHT 自动下载出来的。

## 3. 纯 Magnet 公网 DHT 实测（本次没有找到 Peer）

Windows 发起真实 UDP get_peers：
- router.bittorrent.com:6881：由生产出站地址安全策略拒绝（DNS 结果中存在被策略拒绝的地址），未放宽策略；
- router.utorrent.com:6881：超时；
- dht.transmissionbt.com:6881：成功响应，首次探测返回 8 个 DHT 节点；随后实际迭代查询 19 个节点，返回 0 个 Peer。
- 集成新增 DhtBootstrapLookup 后重新测试：1 个 Bootstrap 路由器响应，获得 2 个种子节点，0 个 Peer。

因此本次**不能声称仅使用这个不带 tr= 的 Magnet 已经通过 DHT 拿到 Metadata**。
真实 DHT 路由器和 Peer 集合是时变的，下一次执行可能返回不同结果。

## 4. 官方 HTTPS Tracker 实测

官方 .torrent 含两个 announce tracker：
- https://torrent.ubuntu.com/announce
- https://ipv6.torrent.ubuntu.com/announce

用 JDK HttpClient 执行只读 announce 诊断，两个 URL 均返回 HTTP 200；
- 其中返回的 compact peers 列表含一个公网 Peer：185.125.190.59:6923。
- 使用项目原生 PeerMetadataSession 对该地址发起 BEP3/BEP10 元数据请求。
- **远程 Peer 在 BEP3 握手中未声明 BEP10 扩展能力**：抛出 IOException: Peer does not support extensions。
- 该地址可作为 torrent 内容 Peer，但当前不能通过 ut_metadata 从它获取缺失的 .torrent 信息。

注意：官方 Tracker 不在用户最初 Magnet 的 tr= 参数中。上述 Tracker 测试使用了官方 .torrent 元数据提供的额外来源，不等同于原始纯 Magnet 测试成功。

## 5. 本轮项目代码改造

新建：
- backend/src/main/java/cc/ivera/magnet/engine/dht/DhtBootstrapLookup.java
  - 为没有 tr= 的 Magnet 提供可注入的有界 Bootstrap 实现；
  - 通过 DhtQueryClient 对默认路由器发起 get_peers，收集响应节点和候选 peers；
  - 复用 IterativePeerLookup 继续发现 Peer，去重并限制容量；
  - 某个 Bootstrap 节点失败不会直接终止其他节点尝试。
- backend/src/test/java/cc/ivera/magnet/engine/dht/DhtBootstrapLookupTest.java
  - 验证部分路由器失败后成功继续；
  - 验证所有 Bootstrap 不可用时安全结束，不进行空节点遍历。

修改：
- backend/src/main/java/cc/ivera/magnet/engine/discovery/DiscoveryService.java
  - Network 模式注册 DhtBootstrapLookup；
  - 无显式 Bootstrap 且 Tracker 找不到任何 Peer 时自动尝试 DHT Bootstrap；
  - 有显式种子节点时仍可主动 DHT 查询；
  - 如果 Tracker 已返回 Peer 且没有显式种子，不做多余公网 DHT 查询，保证模拟测试确定性和资源预算。
- backend/src/test/java/cc/ivera/magnet/engine/discovery/DiscoveryServiceTest.java
  - 验证没有 tr= Tracker 时也会调用 DHT 发现路径。

## 6. 自动化验证

~~~powershell
mvn -f D:\release\clipfetch\backend\pom.xml -q clean test
~~~

最终成功：Exit Code 0，28 个测试类、63 项测试，0 失败、0 错误、0 跳过。

该成功结果不等于公网 DHT 获取了 Metadata；公网实测结果详见第 3、4 节。

## 7. 下一轮重点

1. 增强 HTTPS Tracker：使用固定目标 IP + 正确 TLS SNI、证书主机名校验，独立测试；目前 HttpTrackerClient 仍只支持明文 HTTP。
2. 增加可信 .torrent 元数据的可选导入/回退流程：限定可信来源、8 MiB 封顶、在接受或缓存前必须严格校验所求 v1 InfoHash；不得把任意用户传来的 URL 用作无约束 SSRF 请求。
3. 改进 DHT Bootstrap 节点池、并行查找、超时重试、IPv6、peers 过滤、节点活性和 KRPC 互操作性。
4. 对授权的另一个带 BEP10/ut_metadata 的 BT 测试种子验证真实 Peer Metadata 下载；不能以 Ubuntu 官方 Peer 未实现 ut_metadata 推导所有磁力下载均不可用。
5. 再推进 P3 PieceScheduler、Checkpoint、FileChannel 真正可恢复的下载任务。当前不允许报告下载可用。

## 8. 兼容性、安全和部署说明

- TORRENT_NATIVE_ENABLED 默认为 false，旧 qBittorrent 生产下载链路保持不变。
- 没有写入用户下载任务、数据库完成进度，也没有启动 ISO 内容下载。
- 保留 TrackerAddressPolicy 默认安全拒绝；不要为连接 Bootstrap 强制开放内网/回环访问。
- 本轮未创建 Git commit。
