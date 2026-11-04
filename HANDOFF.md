# ClipFetch 项目交接（HANDOFF）

更新：2026-11-05（第十五轮）；本轮目标：全局异常处理增加 JSON 请求体语法错误的 400 细分，消除笼统 500 的误导。

> 事实优先级：当前工作树、实际命令/运行结果和当前后端状态 > 本文 > ISSUE.md 历史记录。

## 1. 本轮运行时环境（有结构性变化）

### 验收 Compose 栈（`infra/native-v2/docker-compose.yml`）—— 本轮起作为主要运行方式

- project name `clipfetch-magnet`，网络 `nv2-internal`（已移除 `internal: true`）。**只有 4 个服务/容器**：`nv2-certs / nv2-mysql / nv2-redis / nv2-worker`（第十四轮已**彻底移除 api 服务**及 `infra/native-v2/api/` 目录；宿主后端 8082 是唯一 API 实例；如需恢复容器化 API 从 git 历史取回该 service 与 Dockerfile）。
- **端口映射（绑定 0.0.0.0，局域网可访问）**：`nv2-mysql` 3306、`nv2-redis` 6379、`nv2-worker` 50051 常驻；**宿主 8082 = 宿主机后端**（`mvn spring-boot:run` 或 IDEA 运行，当前由我的后台实例运行）。
- 本机局域网 IP：`192.168.1.2`（WLAN，**Public** 配置文件）。局域网访问：后端 API `http://192.168.1.2:8082`（Spring 默认绑 0.0.0.0），MySQL `192.168.1.2:3306`（root / `clipfetch-acceptance`）。
- **bind mount（第七/十一/十二轮）**：worker/api 的 `/data` → `${MAGNET_V2_STORAGE_ROOT:-d:/release/clipfetch/data/magnet-v2}`；certs/worker/api 的 `/certs` → `${MAGNET_V2_CERTS_DIR:-d:/release/clipfetch/infra/native-v2/.certs}`（均在**仓库内**，与 backend/.env、.env.example 完全一致；`certs` 命名卷已删除；两目录已被 .gitignore 拦截）。
- ⚠️ **安全边界**：Redis 无密码、MySQL root 均对局域网暴露，仅在受信网络使用；`MAGNET_V2_BLOCK_PRIVATE_NETWORKS` 在 api 侧仍为 `true`。
- ⚠️ **`nv2-mysql` 没有数据卷**（设计为一次性验收栈）：容器被 recreate/`compose down` 后数据即丢，靠 API（宿主后端或 nv2-api）启动时 Flyway 自动重建 24 个迁移；账号等业务数据需重录。11-04 10:35 栈被整体重建过一次，库已清空重建。
- 容器内 `nv2-api` 无 yt-dlp（`D:\develop\yt-dlp.exe` 默认值警告），只影响容器内媒体解析（磁力 v2 链路不受影响）。

### 本地 Windows（宿主机开发调试栈）—— 当前主要运行方式

- 宿主后端（8082）是**唯一 API 实例**：`$env:SERVER_PORT='8082'; mvn spring-boot:run`（cwd `backend/`）或 IDEA 运行；读写 `backend/.env` 指向的 127.0.0.1:3306/6379/50051——这些端口由 nv2 栈的 mysql/redis/worker 提供（密码相同可通）。
- `clipfetch-dev-mysql` / `clipfetch-dev-redis` / `clipfetch-dev-worker` 容器**均不存在**；如要回到独立开发栈需先停 nv2 栈（3306/6379 互斥）再按第 5 节第四轮命令重建。
- 前端在宿主机：`npm run dev`（5173，`/api` 与 `/ws` 代理到 8082 宿主后端）。
- 本机实际时区为 **UTC-8**（容器内时间 UTC，比宿主本地时间快 8 小时），排查日志时间时注意换算。

### **重要构建陷阱（本地两轮都踩过）**

本机 Maven 本地仓库是 **`D:\repository`**（不是 `~/.m2/repository`）。根 `pom.xml` 聚合 6 个 `server/native-v2-*` 模块 + `backend`；只启动 `backend`（`-pl backend` 或 IDE 单模块运行）时，`server/native-v2-*` 依赖**从 `D:\repository` 的已安装 jar 解析**，不会使用刚 `mvn compile` 出的 `target/classes`。

改了 `server/native-v2-*` 后必须先安装再重启，否则跑的是旧字节码：

```powershell
mvn -q install -DskipTests -pl server/native-v2-domain,server/native-v2-engine-spi,server/native-v2-application,server/native-v2-infrastructure,server/native-v2-engine-libtorrent,server/native-v2-scheduler
```

验证安装生效：`javap -p -cp D:\repository\cc\ivera\native-v2-application\1.0.0\native-v2-application-1.0.0.jar <类>` 能看到新方法；或看 jar 时间戳。

### 启动排障

- 验收栈端口映射改了但不生效 → 检查 `docker inspect <容器> --format '{{json .HostConfig.PortBindings}}'`；若绑定在但宿主无监听，多半是网络带 `internal: true`（本轮踩坑），去掉后需 **`docker compose down && up -d` 整体重建**（仅 `up -d` 重连网络会丢服务名 DNS 别名，见第 2 节）。
- `UnknownHostException: mysql`（nv2-api）→ 同网络容器 DNS 别名丢失，整体 `down` + `up -d` 重建。
- 宿主机后端 `Communications link failure` → 先 `docker ps -a` 确认 `clipfetch-dev-mysql` 存在与否；当前 3306 被 nv2-mysql 占用时宿主后端会连到 nv2 的库。
- `Port 8082 was already in use` → `Get-NetTCPConnection -LocalPort 8082 -State Listen` 找 PID 并结束；`mvn spring-boot:run` 父进程被结束时子 JVM 可能仍在，需按 PID 单独结束。
- 后端文件日志写在**进程工作目录**下的 `logs/clipfetch.log`；IDE 启动时工作目录不同，日志可能落在别处甚至不落盘。

## 2. 本轮改动（未提交）

**第十五轮追加（HttpMessageNotReadableException → 400）**：[GlobalExceptionHandler.java](backend/src/main/java/cc/ivera/shared/web/GlobalExceptionHandler.java) 新增 `@ExceptionHandler(HttpMessageNotReadableException.class)`（catch-all 之前，含 committed/reset 保护），JSON 请求体语法错误由 500「服务器内部错误」改为 **400「请求体格式错误，请提交合法的 JSON 内容」**。验证：`mvn clean compile` exit 0 → 重启 8082 → ① 坏 JSON POST → HTTP 400 + 新文案；② 正常登录 success=true；③ 错误密码仍 401「邮箱或密码错误」（顺带解释了此前探测遇到的 401，属正常语义）。

**第十四轮追加（移除 compose api 服务）**：应用户要求删除 `docker-compose.yml` 中整个 `api` service（原 standalone profile 段）与 `infra/native-v2/api/`（Dockerfile + .dockerignore）。`docker compose config --services` 输出仅 certs/mysql/redis/worker；`up -d` 无任何 recreate，4 容器持续 healthy，端口不变；宿主后端 v2 健康复核 `connected=true version=2.0.8.0`。docker 侧无残留 api 镜像（仅 certs/worker 镜像）。后果说明：局域网访问 API 走宿主后端 8082（Spring 绑 0.0.0.0）；容器化 API/验收部署能力随删除丧失，需要时从 git 历史恢复。

**第十三轮追加（「v2 下载 button 有问题」根因定位与修复，无代码改动）**：根因链：① 第十二轮把 .env 的证书/存储路径改到仓库内，但**用户 IDE 实例仍跑着旧 .env**（存储根=temp 目录）；② 本轮应用户要求清理 temp，8.6GB 内容被删（删前已校验仓库副本字节一致），旧实例的存储根被掏空；③ 此后上传报「路径必须位于 Native v2 存储根目录内」（FileNativeV2Storage.verifyExistingAncestor 在已删除的 root 下走祖先检查失败）、下载流 500。处置：停掉旧 IDE 实例（PID 32080）→ 用新配置在 8082 重起后端（**当前 8082 为此后台实例**，用户若要回 IDE 调试需先停它）。E2E 验证（diagprobe3）：上传 ubuntu torrent（复用资源秒级 READY）→ 建任务（worker 复用已有 2.9GB 数据快速 VERIFYING→COMPLETED）→ file-ticket 签发 → `Range: bytes=0-1023` 流式 GET 返回 **HTTP 206 / 1024 字节**。结论：非代码缺陷，纯环境配置漂移。附记：① 我的探测曾在建任务时报「服务器内部错误」，实为 PowerShell 转义把 JSON body 弄坏（后端对 JSON 语法错误也返回笼统 500，GlobalExceptionHandler 未细分 HttpMessageNotReadableException，可作后续体验优化点）；② temp 根目录仍被未知进程占用删不掉（内容已清零，空壳目录随进程退出/重启后可删）；③ diagprobe3 下新增 1 条 parse + 1 条 COMPLETED 任务（复用同一 resource，未重复下载）。

**第十二轮追加（运行时目录迁入仓库 + 路径对齐）**：用户把 compose 的 certs/data 默认 bind mount 迁到仓库内（`infra/native-v2/.certs`、`data/magnet-v2`），本轮完成对齐：① 现有证书与 metadata/resources/resume 从 temp 目录拷入仓库对应位置（同 CA，无需轮换）；② `backend/.env`（不入库）与 `.env.example` 的 3 条证书路径与 `MAGNET_V2_STORAGE_ROOT` 全部改为仓库绝对路径（注意 .env 原 STORAGE_ROOT 是相对值 `data/magnet-v2`，实际落 `backend/data/magnet-v2`，与 compose 挂载一直不一致，已修正）；③ `.gitignore` 增加 `infra/native-v2/.certs/`（含 TLS 私钥）与 `/data/` 防入库；④ `docker compose up -d` 重建 certs/worker，证书文件已存在故未重新生成（同 CA 无感切换）；⑤ 验证：临时后端（8083，新配置）注册 diagprobe3 → 健康 `connected=true version=2.0.8.0`，已停。⚠️ nv2-mysql 于 10:5x 又被 recreate（第三次，无卷清库），当前仅 bytedance4j@gmail.com（10:53 重录）；诊断账号 diagprobe3@test.local / Diag123456。**用户 IDE 实例（8082）仍跑着旧 .env**（temp 证书路径，文件仍在故暂可用），下次重启 IDE 后端自动切到仓库路径。提交：`2bcf2c3`（compose/.env.example/.gitignore）+ 本轮 .env.example/HANDOFF。

**第十一轮追加（证书刷新前端对接 + 证书共享根因修复）**：① 前端：`api/magnetV2.js` 新增 `reloadCerts()`（POST engine/certs/reload）；`MagnetEngineWorkspace.vue` 在 v2 不可用提示条内加「刷新证书并重连」按钮（loading 态、成功后用响应健康结构直接更新卡片），`npm run build` 通过。② 证书不同步根因：compose 中 certs/worker/api 三服务的 `/certs` 由命名卷改为 bind mount 宿主目录 `${MAGNET_V2_CERTS_DIR:-d:/release/temp/clipfetch/infra/native-v2/.certs}`，删除 `certs` 卷声明——宿主后端与容器从此读同一套 PEM，卷重建换 CA 后只需把新证书放进该目录再调刷新接口，**不再需要 docker cp 同步**。③ 事件记录：10:35 左右整套栈被外部重建（库清空、CA 重新生成），用户重新注册 bytedance4j@gmail.com；诊断账号 diagprobe2@test.local / Diag123456 在 nv2-mysql。④ 实测：bind mount 生效后在用户的 IDE debug 实例（PID 31528）上直接调刷新接口 → `connected=true version=2.0.8.0`，无需重启。⑤ 当前 8082 由用户 IDEA debug 实例占用，我的后台 mvn 实例未再起（端口冲突，属预期）。

**第十轮追加（彻底修复双实例抢单）**：nv2-api 在 compose 中改为 `profiles: ["standalone"]`——默认 `docker compose up -d` 只起 certs/mysql/redis/worker，宿主后端（8082）独占数据库与解析/下载调度器，配置漂移类故障从根上消除；需要容器化 API（局域网 18082 / 验收部署）时显式 `docker compose --profile standalone up -d`。执行：`docker compose stop api && rm -f api && up -d`（mysql 容器未动，数据保留）。验证：`compose ps` 仅 4 容器；重启宿主后端后健康 `connected=true version=2.0.8.0`；重提 ubuntu 磁力 → `READY totalBytes=2918598656`。当前默认栈端口：3306/6379/50051 仍 0.0.0.0 全开放；8082 宿主后端；18082 仅 standalone profile 生效。

**第九轮追加（v2「总大小超过上限」修复）**：现象：解析 ubuntu-26.04 ISO（2.9GB）FAILED「总大小超过上限」。根因：宿主后端与 nv2-api 容器**共用 nv2-mysql 且各自跑解析调度器**，nv2-api compose env 的 `MAGNET_V2_MAX_TOTAL_BYTES` 还是 1GiB（1073741824），任务被 nv2-api 抢单按 1GiB 拒绝；宿主 .env 实为 1TiB（1099511627776，mtime 08:07 一直如此，非用户新改）。修复：compose 中 nv2-api 该值改为 1099511627776 并 `docker compose up -d api` 重建。验证：探针账号重提同一磁力 → `READY, name=ubuntu-26.04-live-server-amd64.iso, totalBytes=2918598656, files=1`。**架构陷阱（遗留）**：宿主后端与 nv2-api 双实例共用一库、双调度器抢任务，两侧 v2 配置（限额/超时等）不一致就会产生此类诡异失败；建议二选一跑 API，或保持两侧 env 完全同步。探针账号 diagprobe@test.local / Diag123456 下有若干测试 parse 记录（含 FAILED），属验收数据。

**第八轮追加（v2 证书刷新接口）**：新增 `POST /api/magnet/v2/engine/certs/reload`（需登录），运行时重读 `.env` 配置的证书文件并重建 gRPC 通道，免去换证书后重启后端；返回最新健康结构。改动：① `server/native-v2-engine-spi/.../NativeV2Engine.java` 加 `default void reload()` 空实现；② `GrpcNativeV2Engine` 的 `channel` 改 `volatile`，新增 `reload()`（先建新通道再关旧通道，证书加载失败保留旧通道），`close()` 与新 `reload()` 共用 `shutdownQuietly`；③ `NativeV2HealthController` 加端点，未启用/未配置抛中文 BusinessException。同时修复 v2 不可用根因：`infra/native-v2/certs/generate-certs.sh` 的 server.crt SAN 由仅 `DNS:worker` 扩为 `DNS:worker,DNS:localhost,IP:127.0.0.1`（宿主后端按 IP 连接时 TLS 主机名校验才能过），CA/证书有效期 2 天→365 天；重建 certs 镜像 + 删 `clipfetch-magnet_certs` 卷重建栈 + `docker cp` 同步新证书到宿主 `.certs/`。验证：`mvn install -pl server/native-v2-engine-spi,server/native-v2-engine-libtorrent` exit 0；`mvn clean compile` exit 0；重启后端后实测 `POST /api/magnet/v2/engine/certs/reload` 返回 `{success:true, data:{configured:true, connected:true, version:"2.0.8.0"}}`。前端「刷新引擎状态」按钮仍只调 GET health，未接新接口（用户仅要求出接口）。注意：server 模块改动必须先 `mvn install` 对应模块再重启后端才生效（Maven 仓库快照陷阱）。

**第七轮追加（v2 不可用修复）**：宿主后端（8082）报「Native v2 Worker 未连接」，两处断点：① nv2-certs 重建 CA 后，宿主 `.certs/` 还是旧 CA，mTLS 握手失败 → 已用 `docker cp nv2-certs:/certs/.` 覆盖同步到 `d:/release/temp/clipfetch/infra/native-v2/.certs/`（server.crt 1155→1131 证实 CA 确实变了）；② nv2-worker 的 `/data` 是命名卷 v2data，宿主后端写的 metadata 不可见 → compose 中 worker 与 api 的 `/data` 均改为 bind mount `${MAGNET_V2_STORAGE_ROOT:-d:/release/temp/clipfetch/data-magnet-v2}:/data`，顶层 `v2data` 卷随之删除。验证：`up -d` 后 5 容器 Up（nv2-api 18082、worker 50051 healthy）；`docker exec nv2-worker ls /data` 与宿主目录一致（metadata/resources/resume）。**宿主机后端需重启加载新证书**后 v2 恢复；v2data 旧卷中数据未迁移（视为验收数据丢弃）。

**第六轮追加**：nv2-api 端口映射由 `"8082:8082"` 改为 **`"18082:8082"`**（容器内应用仍监听 8082，只换宿主侧端口）。原因：宿主 8082 是宿主机后端固定端口，两者共存冲突（`Port 8082 was already in use`）。改动已写入 compose 文件；**执行时 Docker Desktop 未运行且命令行无法拉起**（`docker-desktop` WSL 发行版 Stopped），需手动启动 Docker Desktop 后在 `infra/native-v2` 执行 `docker compose up -d` 生效。生效后期望：宿主 8082 空闲、宿主/局域网 `18082` 可达 nv2-api。

**第五轮**：仅改 `infra/native-v2/docker-compose.yml` 一处文件：

1. `mysql` / `redis` / `worker` / `api` 四个服务各加 `ports:` 映射（3306 / 6379 / 50051 / 8082）。
2. `networks.default` 删除 `internal: true`。

**为什么删 `internal: true`**：加了 `ports:` 并 `up -d` 后，容器配置里 `PortBindings` 存在（`{"8082/tcp":[{"HostIp":"","HostPort":"8082"}]}`），但宿主机 netstat/Test-NetConnection 全部不通——`internal: true` 的内部网络在 Docker Desktop 下会静默阻断端口发布转发。移除后 3306/6379/50051 立即可达。

**第二个坑**：仅移除 internal 后 `up -d` 只重启不重建议容器，`nv2-mysql` 重连新网络时**丢了服务名 DNS 别名**（`DNSNames` 只剩 `nv2-mysql`，没有 `mysql`），导致 `nv2-api` 启动报 `UnknownHostException: mysql` 崩溃退出；`--force-recreate api` 无效。必须 `docker compose down && up -d` 让全部容器新建网络附件。

## 3. 本轮验证（真实结果）

| 命令 / 操作 | 真实结果 |
|---|---|
| `npm run build`（frontend，证书按钮改动后） | Exit 0，构建通过 |
| `docker cp nv2-certs:/certs/.` → 宿主 `.certs/` + md5 对比 | 卷内与宿主 `ca.crt` 指纹一致（`837178b9...`） |
| compose `/certs` 改 bind mount 后 `docker compose up -d` | nv2-certs / nv2-worker 重建并 healthy；mysql/redis 未动（数据保留） |
| `POST /api/magnet/v2/engine/certs/reload`（diagprobe2，**用户 IDEA debug 实例 PID 31528，未重启**） | `success=true, connected=true, version=2.0.8.0` —— 证书同步+接口重载全链路闭环 |
| 前轮回归：重提 ubuntu 磁力（单实例拓扑） | `READY, totalBytes=2918598656` |
| `docker exec nv2-mysql ... SELECT email FROM fvd.user` | bytedance4j@gmail.com（10:35 重建后重录）、diagprobe2@test.local |

未验证边界：前端「刷新证书并重连」按钮未做浏览器端点击 E2E（仅 API 级验证 + 构建通过）；局域网访问未从其他物理机器实测（Public 防火墙需管理员放行 8082/3306/6379/50051）。

## 4. 遗留事项与下一步

1. nv2-mysql 无数据卷：`compose down` / recreate 即清库；要持久化需加 `mysql-data:/var/lib/mysql` 卷（一直按最小改动未加）。
2. **证书轮换标准动作**（CA 过期/重建后）：删 `infra/native-v2/.certs/`（仓库内）旧文件 → `docker restart nv2-certs nv2-worker`（证书直接生成到该目录）→ 页面点「刷新证书并重连」。无需 docker cp、无需重启后端。
3. ~~standalone profile 双实例风险~~（第十四轮 api 服务已删除，条目作废；容器化 API 如需恢复，注意其 env 必须与 `backend/.env` 的 v2 参数完全同步——第九轮限额漂移即双实例配置不一致所致）。
4. 前端未容器化：仍宿主机 `npm run dev`；如需整套 Docker 化（含 5173 与 yt-dlp/ffmpeg 挂载）需单独立项。
5. 历史遗留仍在：`bindNew()` race 孤儿 torrent；v1/v2 登录态 blob 下载未做浏览器 E2E；页面偶发 500 未定位。
6. server 模块**完全没有日志**（无 `@Slf4j`），gRPC 调用链排障只能靠 DB `error_message`。
7. ~~各轮改动均未 commit~~ **已于 2026-11-05 一次性提交本地**：`b495032 feat(magnet): 拆分 v0/v1/v2 限界上下文，修复 Native v2 全链路并重建验收栈`（185 文件，+1909/-1711，分支 feature，**未 push**）；`backend/.env` 不入库。

## 5. 历史改动（前四轮，均未提交）

### 第四轮：宿主后端 `Connection refused`（MySQL 连不上）

宿主后端报 `Communications link failure` 不是配置错误——开发栈 `clipfetch-dev-mysql` / `clipfetch-dev-redis` 已被整体删除，宿主 3306 无人监听；用户启动的 nv2 栈当时无端口映射。按 README 重建开发容器即恢复（注意 root 密码用 `.env` 的 `DB_PASSWORD=clipfetch-acceptance`，README 示例 `clipfetch` 与实际不符）：

```powershell
docker run -d --name clipfetch-dev-mysql -p 3306:3306 -e MYSQL_ROOT_PASSWORD=clipfetch-acceptance -e MYSQL_DATABASE=fvd mysql:8.4 --sql-mode=STRICT_TRANS_TABLES --character-set-server=utf8mb4
docker run -d --name clipfetch-dev-redis -p 6379:6379 redis:7
```

验证：全新空库 Flyway 24 迁移通过，`Started ClipFetchApplication in 7.822 seconds`。旧开发库数据随容器删除丢失。（第五轮起该套容器再次被删，运行方式切到 nv2 栈。）

### 第三轮：验收栈 `docker compose up -d` 构建失败

`infra/native-v2/api/Dockerfile` 的构建上下文是仓库根，但 `native/` 已迁到 `infra/native-v2/native/`，`COPY native/proto ./native/proto` 源不存在。确认该 COPY 对 Maven 构建无任何作用（根 `pom.xml` 无 proto 模块；`GrpcNativeV2Engine` 用原始字节调 gRPC，不依赖生成类）后，删除该 COPY 及 `Dockerfile.dockerignore` 中失效的 `!native/proto/**`。验证：`docker compose build api` Exit 0；`up -d` 后 5 容器全部 Up；`nv2-api` 内 Flyway 24 迁移通过、Tomcat 8082 启动。

### 第二轮：v2 下载任务「失败 · 35.1% · Native v2 Worker 调用失败」

**根因**：`NativeV2TaskService.delete()` 无条件 `engine.remove(...)`，而 Worker 的 fence 是**按资源共享**的——删除同资源的历史任务会把另一个正在下载任务的会话一并拆掉，下一次快照失败；`GrpcNativeV2Engine.mapStatus()` 的 `default` 分支还把真实状态吞成「Native v2 Worker 调用失败」，任务直接 FAILED。

**修复**：

- `NativeV2TaskService.delete()`：先查 `sessions.find(resourceId)`，仅当 `active_task_id` 为空或等于本任务时才释放 Worker 会话；否则只删 DB 记录。
- `NativeV2TaskDispatcher.refresh()`：快照抛 `CONFLICT` 时改为 `start(task, resource)` 重新挂载，而不是失败。
- `NativeV2TaskDispatcher.start()`：带 resume 的挂载失败后，无 resume 重试改用**新 command_id**（`:RESTART`），避免 Worker 按 command_id 缓存同一拒绝。
- `GrpcNativeV2Engine.mapStatus()`：文案带 gRPC 状态码 + description；`FAILED_PRECONDITION` → `CONFLICT`。

**验证**：Ubuntu 2.9GB 正常 `27.7% → COMPLETED`；下载途中删同资源已完成任务不影响活跃任务；删活跃任务仍能释放 fence（worker 保持 Up）；同资源第二个活跃任务返回 409。

### 第一轮：v2 复用旧资源时 metadata 丢失

`NativeV2ParseDispatcher.resolve()` 命中既有资源后不校验 metadata 文件，旧资源 `70fb2a16-...` 的 `.torrent` 已丢 → 创建任务抛 `INPUT_LOST`「metadata 已丢失」。修复：`metadataIntact()` 校验 + `ensureMetadata()` 幂等补写 + `bindNew()` race 分支走 `bindReadyWithMetadata()`；`FileNativeV2Storage.readMetadata()` 文案改为可操作提示。验证：metadata 缺失 → 补写 223068 字节 → 任务 `QUEUED → COMPLETED`。

### 更早

- 后端启动失败（MyBatis mapper 引用旧包名）：4 个 mapper XML 的 `type`/`parameterType` 指向重构前的包，逐个替换（不可全局替换，否则 `namespace` 会变 `v1.v1.domain`）。
- v1 Ubuntu 磁力元数据解析失败：`HttpTrackerClient` 去掉 `&compact=1`；`TrackerProtocol` 支持 `compactIpv6()`/`dictModelPeers()`；`DiscoveryService` 提高 peer 池与探测数；`MetadataResolver` IPv4 优先 + IPv6 兜底。
- v1 / v2 下载改「登录态 + 浏览器下载」：仅 qBittorrent v0 保留票据 stream 白名单；前端 `responseType:'blob'` + objectURL（大文件内存占用是已知边界）。
- v2 Worker fence 崩溃（退出码 75）：终态/失败/暂停/删除路径主动释放 fence。
- v2 任务删除接口 + 前端删除按钮；修改密码接口与弹窗；v2 已完成任务一键下载。
- magnet 包重构 v0/v1/v2 + 7 个 mapper XML namespace 同步。
- `native/` → `infra/native-v2/native/`，Worker 镜像构建修复（去掉 `target: runtime`、`resource_fences.h` 命名空间、proto 包名、gRPC/pb include）。

## 6. v2 不可用排查记录

- 2026-11-04 早期：`clipfetch-dev-worker` 因 fence 租约过期被 watchdog `_Exit(75)`，无重启策略 → 手动 `docker start`；根因修复为终态主动 `remove(removeData=false)`。
- Worker 日志每 15 秒 SSL `wrong version number` 来自 Docker healthcheck（明文连 TLS 端口），噪音，可忽略。
- 第二轮：用户删除旧任务触发 `engine.remove` 拆掉同资源活跃任务会话，是「Worker 调用失败」的直接诱因，已由 `delete()` 的会话归属守卫修复。
- 第三轮：`docker compose up -d` 构建失败与运行逻辑无关，是 Dockerfile 中指向已迁移目录的 `native/proto` COPY；该拷贝对 Maven 构建无用，已移除。
- 第四轮：宿主后端 `Connection refused` 是开发栈 `clipfetch-dev-mysql`/`redis` 容器被删除；重建即恢复。
- 第五轮：验收栈加端口映射不通的根因是网络 `internal: true` 静默阻断端口发布；网络重建后服务名 DNS 别名丢失导致 `UnknownHostException: mysql`，需整体 `down` + `up -d`。
