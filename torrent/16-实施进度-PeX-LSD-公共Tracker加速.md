# ClipFetch 自研 BitTorrent · PeX/LSD/公共 Tracker 加速交接

> 日期：2026-10-14；项目 D:\release\clipfetch
> 状态：完成独立协议与显式 opt-in 的聚合调用、测试；Native 下载仍不可用于生产。
> 保持 TORRENT_NATIVE_ENABLED=false，qBittorrent 现有生产下载不变。

## 协议区别

- DHT：公网分布式 Peer 发现；
- PeX (BEP11)：从已有的、支持 BEP10 与 ut_pex 的 Peer 取得额外 Peer；没有起始 Peer 则不能仅靠 PeX 发现。
- LSD (BEP14)：239.192.152.143:6771 局域网组播；不属于公网 Tracker；只允许指定私有 IPv4 网卡、显式开启，默认不广播。
- Public Tracker：由可信目录选出 HTTP/UDP Tracker，再实际向其发送该 InfoHash 的 announce；响应成功不等于必然有 Peer。
- Private torrent 严禁向公网 DHT、PeX、LSD 或额外公共 Tracker 暴露其 InfoHash。调用方需先确认是合法公开 swarm；这些发现通道始终默认关闭。

官方 BEP：
https://www.bittorrent.org/beps/bep_0011.html
https://www.bittorrent.org/beps/bep_0014.html
https://www.bittorrent.org/beps/bep_0027.html

## 新增代码

- engine/pex/PeerExchangeCodec.java
  - 解析 added, added.f, added6, added6.f, dropped, dropped6；IPv4/IPv6 compact、flags 长度与上界校验。
- engine/pex/PeerExchangeSession.java
  - 实际 TCP BEP3 握手，BEP10 ut_pex 协商，有限次数读取 PEX delta；InfoHash 验证、超时；明确拒绝 private。
  - 仍是一次性收集，无长期 PeerSession、周期推送与掉线同步。
- engine/lsd/LsdAnnouncementCodec.java
  - BEP14 BT-SEARCH 编解码，InfoHash/Port/Cookie 和源 IP 验证，忽略本机报文，拒绝公网来源。
- engine/lsd/LsdPeerDiscovery.java
  - 显式指定 LAN 网卡的 TTL=1 组播发现；收到的候选 Peer 来自 UDP 源 IP；最长 5 秒；及时关闭 socket。
  - 没有安排定时任务或后台广播，也未做真实多机局域网兼容测试。
- engine/tracker/TrustedTrackerCatalog.java
  - 固定 HTTPS 的可信开源 trackers_best 源和官方镜像源，不接受任意远程 Feed URL；
  - 只筛选支持的 HTTP/UDP URL，最多 20 条、30 分钟缓存、去重，拒绝重定向与过大响应。
  - 来源：https://github.com/ngosang/trackerslist
- engine/tracker/PublicTrackerProbe.java
  - 明确已验证公开 swarm 才能操作，最多检查 12 个候选 Tracker；真实 announce，记录响应状态、耗时、有效 Peer 数；地址过滤、去重。
- engine/peer/PeerCandidatePool.java
  - 追加 PEX、LSD、PUBLIC_TRACKER 来源；LSD 私网 IPv4 专用接收入口与容量限制。
- engine/discovery/PeerDiscoveryAccelerator.java
  - 公共 torrent 专用 PeX、LSD 和额外 Tracker 的组合；PeX 最多连接前两个候选 Peer；LSD 必须网卡与开关；无权限全部跳过。
- engine/discovery/DiscoveryService.java
  - 新增显式 discoverAccelerated 入口，默认 discover 路径不变。
- engine/discovery/MetadataResolver.java
  - 新增 resolvePublicAccelerated 入口；未验证公开种子拒绝额外加速。
- interfaces/NativeTorrentController.java
  - 健康诊断阶段更新为 P3-protocol-prototype；ready=false、downloadAvailable=false、discoveryAccelerationAutoEnabled=false。

以上 engine/ 文件均以 backend/src/main/java/cc/ivera/magnet/ 为前缀。

## 新增测试范围

- engine/pex/PeerExchangeCodecTest：compact peers、flags 与非法数据边界；
- engine/pex/PeerExchangeSessionTest：本机 ServerSocket 模拟 TCP BEP3、BEP10 和 ut_pex；Private 拒绝；
- engine/lsd/LsdAnnouncementCodecTest：内网源、错误 InfoHash、自身 cookie；
- engine/tracker/TrustedTrackerCatalogTest：Feed 去重/禁用、支持协议筛选、在线 Tracker 真实响应判定的模拟；
- engine/discovery/PeerDiscoveryAcceleratorTest：候选来源合并、私有种子门控、LAN 接入限制；
- engine/discovery/AcceleratedMetadataResolverTest：原始 Peer 无 metadata 时由 PeX 候选回退并获取正确 v1 metainfo，private 拒绝。

## Ubuntu 26.04 公网 Tracker 结果

基于用户此前确认的公开 Ubuntu 26.04 InfoHash：
dafc8c076ca2f3ed376eeae7c76a0d6be2415c45

本次使用真实 PublicTrackerProbe.network 执行 4 个公共 UDP Tracker 的 announce：
- tracker.opentrackr.org:1337 - 响应成功，新增 155 Peer，约 600ms；
- open.stealth.si:80 - 响应成功，新增 12 Peer，约 537ms；
- tracker.torrent.eu.org:451 - 响应成功，新增 5 Peer，约 605ms；
- open.demonii.com:1337 - 响应成功，新增 4 Peer，约 2763ms。

总计 4 个 Tracker 回应、176 个去重候选。未逐一 TCP 校验 Peer，也未下载 Ubuntu ISO，因此不能宣称 Metadata 或完整文件已经下载成功。
Tracker 列表是动态数据，结果受网络环境及测试时间影响。

## 安全和生产化未完成

1. 未实现运行时对 private=1 的自动强制判定，Options.verifiedPublicSwarm 目前属于受信任上层代码的明确声明，不应由任意用户输入决定。
2. 原生 Worker 及 UI 暂未打开自动增强，需加速预算、用户授权、隐私控制和后台取消后才能对外开放。
3. PeX 尚需长期会话及对 dropped、Peer 活性、限速的处理；目前只实现一次有限窗口的收集。
4. LSD 未在真实多机 LAN 测试组播、Windows 防火墙、网卡选择、UDP 6771 以及隐私影响。
5. Public Tracker 的网络源在部分 JDK 环境可能需要配置代理；当前只允许 fixed HTTPS Source/Mirror，不允许用户任意传 URL。
6. HTTPS Tracker 本体、持久任务下载、Checkpoint、任务调度及 UserID+TaskUUID 进度隔离仍未完成。

继续遵守 AGENTS.md：不覆盖已有未提交修改、不改旧 Flyway、不加入 Redis/MQ/微服务、不主动创建 Git commit。
