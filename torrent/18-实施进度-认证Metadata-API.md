# ClipFetch 自研 BT · 18 认证异步 Metadata API 交接

> 日期：2026-10-14；工作区 D:\release\clipfetch
> 当前为默认关闭的元数据解析 API 原型，不是正式下载引擎。

## 实际代码

- backend/src/main/java/cc/ivera/magnet/interfaces/NativeMetadataJobsController.java：实现已认证 HTTP 提交、状态、文件分页和取消。身份取自 AuthInterceptor.ATTR_USER；客户端不能提交 userId。
- backend/src/main/java/cc/ivera/magnet/engine/discovery/NativeMetadataJobManager.java：新增按 userId+jobId 查询文件分页，任务回写时再次检查是否超过总预算。
- backend/src/main/java/cc/ivera/magnet/engine/config/NativeTorrentConfiguration.java：注入可由 Spring 销毁的单例 Metadata Job Manager。
- backend/src/main/java/cc/ivera/magnet/engine/config/NativeTorrentProperties.java：新增元数据查询开关、并发线程、队列、单用户限制、总超时秒数和 Listen Port 配置。
- backend/src/main/resources/application.yml 与根目录 .env.example：追加 TORRENT_NATIVE_METADATA_* 配置，默认关闭。
- backend/src/main/java/cc/ivera/magnet/interfaces/NativeTorrentController.java：健康检查新增 metadataLookupEnabled、metadataJobsInMemoryOnly；downloadAvailable=false。

## API 清单

- POST /api/magnet/native/metadata/jobs：JSON 磁力链接 magnetUri + consentToPublicDiscovery=true，返回 jobId、status。
- GET /api/magnet/native/metadata/jobs/{jobId}：查询当前登录用户任务状态。
- GET /api/magnet/native/metadata/jobs/{jobId}/files?page=1&pageSize=20：解析成功后每页最多 30 个文件。
- DELETE /api/magnet/native/metadata/jobs/{jobId}：取消当前登录用户的队列任务或正在解析的任务。
- GET /api/magnet/native/engine/health：检查功能开关，原生下载始终不宣称可用。

## 配置（仅合法授权测试环境手动开启）

TORRENT_NATIVE_METADATA_LOOKUP_ENABLED=true
TORRENT_NATIVE_ENABLED=false

生产默认均为 false。纯 Magnet 公开 DHT/Tracker 查询可能披露 InfoHash，故提交前必须显式 consentToPublicDiscovery=true；该参数是确认，不是认证 public torrent 的依据。

## 验证

- mvn -f D:\release\clipfetch\backend\pom.xml -q clean compile -DskipTests：Exit Code 0。
- mvn -f D:\release\clipfetch\backend\pom.xml -q clean test：Exit Code 0。
- 新增 interfaces/NativeMetadataJobsControllerTest.java：使用 MockMvc standalone 验证认证上下文、默认开关、隐私确认、提交、任务和文件分页、跨用户拒绝、取消。
- 扩展 discovery/NativeMetadataJobManagerTest.java：即使 resolver 超预算后返回成功，也只能是 TIMED_OUT。
- 已检查 WebConfig 所有 /api/** 均由 JWT AuthInterceptor 保护，新端点不在免登录例外内。注意 MockMvc standalone 未执行完整 JWT + 数据库认证链路。

## 未完成

1. Metadata Job 暂存在 JVM 内存，重启丢失，未集成旧磁力解析历史；尚无用户下载进度或数据文件。
2. 底层 DNS 和阻塞 I/O 不保证 Future.cancel(true) 立刻停止；Job 超时是可见状态约束，仍须补充强制网络截止时间。
3. 纯 Magnet 自身没有 private=1 可信信息，显式披露确认不等于证明公开；已验证私人种子必须走上一轮 discoverVerified 限制。
4. 尚未把 PeX/LSD/公共 Tracker 额外加速接入此默认 Worker，未实现完整 Vue UI。
5. P3 PieceScheduler、Checkpoint、恢复和 userId+taskUuid 下载状态机尚未完成。

原有 qBittorrent 生产路径不变；未提交 Git，未修改已发布的 Flyway 或引入 MQ/Redis/定时任务。