# ClipFetch 自研 BitTorrent · P2 HTTP Tracker 联通交接

> 日期：2026-10-14 · 本轮成功运行 Maven clean test（exit code 0）。
> 阶段状态：HTTP Tracker 明文 HTTP 的真实请求、响应读取和本地网络 fixture 已落地。**尚非完整 HTTP(S) Tracker 客户端，更不是完整下载引擎**。

## 1. 实际落地代码

- backend/src/main/java/cc/ivera/magnet/engine/tracker/HttpTrackerClient.java
  - 构建 BEP 3 announce 请求，info_hash / peer_id 通过原始二进制 %XX 编码。
  - TCP Socket 连接到 TrackerAddressPolicy 解析且固定的 IP，HTTP Host 保留原域名。
  - GET /announce 发送 downloaded/left/uploaded/compact/port 参数。
  - HTTP 状态严格限制为 200，禁止隐式跟随跳转。
  - 有界 HTTP Header / Body，Content-Length 校验，套接字连接/读取超时。
  - 与 TrackerProtocol.parseHttpResponse 整合，解析 compact peers 和 announce interval。
  - 明确只支持明文 HTTP：HTTPS 不可默默降级；需要后续设计支持 SNI、证书主机名校验及 IP 固定。
  - 暂不支持 Transfer-Encoding: chunked，以及非 compact peers。
- backend/src/test/java/cc/ivera/magnet/engine/tracker/HttpTrackerClientTest.java
  - 启动 ServerSocket 本地 fixture，验证 announce 请求与 compact peers 响应。
  - 验证默认模式禁止访问 loopback Tracker。
  - 验证 HTTPS 请求在未实现 TLS 前明确拒绝。
- 沿用上轮 TrackerAddressPolicy 和 TrackerProtocol，不改旧 QBittorrentClient。

## 2. 测试

执行：
~~~powershell
mvn -f D:\release\clipfetch\backend\pom.xml -q clean test
~~~
结果：进程 exit code 0。本轮 HTTP Tracker loopback fixture 随测试执行成功。

## 3. 安全性与尚未完成部分

- 默认 TrackerAddressPolicy 仅是初步 IP 阻断策略：必须进一步审计 IPv6 映射地址、保留地址段、DNS 重绑定等情况。
- 当前 HTTP 客户端不跟随重定向；这保护了安全边界，但部分 Tracker 兼容性仍需后续评估。
- 当前 HTTP 请求建立的是独立短连接；未提供 HTTP Keep-Alive/连接池，也没有复杂重试机制。
- 如果 HTTP 响应是 chunked，当前明确报错，不会将其错误地当成普通 bencode 内容。
- HTTPS Tracker、真实 DHT find_node/get_peers、Peer TCP、BEP9 metadata、Piece 下载、用户任务持久状态机均未完成。
- 不允许因为 HTTP fixture 通过而把原生引擎设置为 ready=true；NATIVE_ENABLED 默认 false，现有 qBittorrent 下载仍是生产路径。

## 4. 下一轮开发优先级

1. 扩充 TrackerAddressPolicy 的公共/非公共地址分类测试，并在具体连接处坚持 IP 固定。
2. HTTPS Tracker 采用 TLS 证书与原 Host 正确绑定的安全实现；或在明确条件下继续保持不支持。
3. HTTP Tracker chunked / noncompact peers / peers6（按优先级逐项补齐）。
4. 完整 DHT find_node/get_peers 网络交互：响应 nodes/values/token，最近节点迭代与限额。
5. TCP Peer 和 BEP10/BEP9 metadata，构建无需 qBittorrent 的合法种子文件树获取闭环。
6. 后续 P3/P4 才是正式 Piece 下载、文件落盘、断点续传和用户 taskUuid 进度隔离。

## 5. 用户任务隔离约束

- 资源 InfoHash 不能替代 taskUuid；Tracker 客户端仅负责发现 Peer，不拥有用户进度。
- 共用 Tracker 发现结果不构成共享下载会话、文件选择或任务控制。
- 任何暂停/删除进度查询都必须限制当前已登录 userId 和具体 taskUuid。
- 未创建 Git commit；不得改写旧历史迁移文件。
