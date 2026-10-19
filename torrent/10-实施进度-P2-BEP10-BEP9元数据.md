# ClipFetch 自研 BitTorrent · P2 BEP10/BEP9 元数据协议交接

> 日期：2026-10-14；工作区 D:\release\clipfetch
> Maven 全量 clean test 执行成功（exit code 0）。
> 状态：元数据协议编解码与校验器已实现；**Peer 长连接及真正从远程 Peer 获取 Metadata 仍未完成**。

## 本轮交付

- backend/src/main/java/cc/ivera/magnet/engine/metadata/MetadataExtensionCodec.java
  - BEP10 扩展握手，声明 ut_metadata 能力；
  - 解析远端 ut_metadata 扩展消息 ID、metadata_size；
  - 编码 BEP9 metadata 分片请求；
  - 解析 BEP9 metadata 消息（bencode header 与尾部原始二进制数据），限制长度及类型。
- backend/src/main/java/cc/ivera/magnet/engine/metadata/MetadataAssembler.java
  - 每会话独立原始 info 字典缓冲区（默认最大 8MiB）；
  - 16KiB 元数据分片、任意顺序重组、重复分片冲突检查；
  - 分片总长、下标、分片长度约束；
  - 组装完成后 SHA-1 与预期 infoHash 比较，并检查 bencode 字典有效性。
- backend/src/test/java/cc/ivera/magnet/engine/metadata/MetadataProtocolTest.java
  - BEP10 握手、BEP9 元数据请求/响应协议解析测试；
  - 正确 SHA-1 的成功路径、错误 InfoHash 拒绝；
  - 乱序分片、重复分片以及无效字典的失败路径。

## 验证

命令：
~~~powershell
mvn -f D:\release\clipfetch\backend\pom.xml -q clean test
~~~
结果：Exit Code 0。

## 后续明确未完成

1. 目前只有纯协议组件，没有在一个持续 TCP Peer Session 上执行 BEP10/BEP9 双向消息收发。
2. 尚未从任意真实 Peer 获取 metadata；本阶段仅测试本地构造的协议消息。
3. 元数据返回的是已校验原始 info 字典字节，正式业务仍需转换为 torrent metainfo、文件列表，并与解析历史整合。
4. 多用户文件下载、暂停恢复、PieceScheduler、存储和进度仍未完成；不应启用 Native 下载。
5. Tracker/DHT Peer 候选的实际网络互操作与生产级地址安全审计尚待完善。

## 下一轮

- 将 PeerConnector 升级为有界持续 TCP 会话，读取扩展握手中的 ut_metadata ID 和 metadata_size；
- 对 MetadataAssembler 分片调度进行逐块请求、响应对应关系、超时和 peer 失败处理；
- 使用本地模拟完整 BEP10/BEP9 Peer 验证端到端元数据获取；
- 与 TorrentV1MetainfoParser 连接，返回经过 InfoHash 验证的实际文件清单；
- 之后再开展公网授权种子的互操作性测试与 P3 下载核心。

## 兼容性约束

维持 TORRENT_NATIVE_ENABLED=false，旧 qBittorrent 下载链路不变。所有实际用户任务依赖 userId+taskUuid 隔离。未创建 Git commit。
