# NetMirror TMDB Key 动态提取、持久化与失效覆盖 技术规格（SPEC）

> 日期：2026-11-24（Asia/Shanghai）
> 状态：设计合同，尚未实施
> 仓库：D:\release\clipfetch
> 实施计划：[2026-11-24-netmirror-tmdb-key-plan.md](../plans/2026-11-24-netmirror-tmdb-key-plan.md)
> 项目约束：AGENTS.md、HANDOFF.md、ISSUE.md
> 说明：本规格只定义实现与验收合同，不代表功能已完成；本轮不修改业务代码、不构建、不启服务、不创建 commit。

---

## 1. 目标与验收标准

### 1.1 目标

消除 [NetMirrorParser.java](../../../backend/src/main/java/cc/ivera/parser/application/parser/video/NetMirrorParser.java) 中对硬编码常量 `TMDB_KEYS` 的静态依赖，建立一套「**数据库持久化 + 冷启动自动提取保存 + 失效自动检测覆盖 + 受鉴权 REST 管理接口**」闭环：

- 数据库无记录时，自动从 NetMirror 官网入口页定位前端 JS bundle，提取 TMDB v3 API Key 列表、验活并入库；
- 正常解析请求直接复用内存快照与数据库记录，不重复抓取官网 JS；
- 当库中所有 Key 均触发鉴权失效（`HTTP 401` / `403`）时，自动重新抓取官网最新 JS、验活并覆盖更新数据库，随后重试当次 TMDB 元数据请求；
- 提供受 JWT 保护的 REST 管理接口，支持查询当前脱敏状态、手动强制刷新覆盖，以及手动录入覆盖自定义 Key 作为兜底手段。

### 1.2 完成标准

1. **空库冷启动**：清空 `parser_runtime_config` 中 `netmirror.tmdb_keys` 记录后，解析任意 `/tmdb/watch/{movie|tv}/{id}` 链接，服务端自动抓取 NetMirror 入口 HTML 与 JS bundle、提取并验活 TMDB Key、落库并成功返回视频信息。
2. **常规热路径零额外抓取**：库中已有有效记录且未失效时，重复解析 `/tmdb/watch/` 不再请求官网首页或 JS bundle。
3. **失效自动覆盖**：将库中 `netmirror.tmdb_keys` 设为无效 32 位 Key 后再次解析 `/tmdb/watch/`，服务端命中 `HTTP 401` 后自动触发一次重新抓取与验活、**覆盖更新数据库记录**，并在同一请求内自动重试返回 `200` 解析结果。
4. **单次重试与冷却保护**：单次 `fetchTmdbMeta` 最多触发 1 轮自动刷新重试，不出现无限循环；连续失效时自动刷新遵守冷却间隔（默认 60 秒），多线程并发失效只发起 1 次官网抓取。
5. **限流不误刷**：单个 Key 返回 `HTTP 429` 时仅切换列表内下一把 Key，不误判为 Key 永久失效去覆盖数据库。
6. **验活保护不污染库**：自动抓取若未能提取到任何有效 Key，拒绝覆盖数据库原有记录，返回明确中文错误信息。
7. **REST 接口与鉴权合规**：`/api/netmirror/tmdb-keys` 三个端点未携带有效 JWT 返回 `401`；响应中所有 Key 仅展示脱敏格式（如 `c8de****f7ad`）；日志不输出任何完整明文 Key。
8. **双库迁移与既有链路无回归**：Flyway `V28` 在 SQLite 与 MySQL 均能正常执行；旧版 NetMirror `/tv/{id}`、`/movie/{id}` 及其他平台解析/下载/在线播放无回归。

### 1.3 非目标

- 不改造旧版 `/tv/{id}`、`/movie/{id}` 元数据链路（`api2.imdb3.shop`）与 `SIG_KEY` 签名逻辑（但表结构保留通用扩展能力）；
- 不新增前端管理页面（本期仅提供后端自动闭环与 REST 接口，后续按需在前端接入）；
- 不引入定时任务（`@Scheduled`/cron）、Redis 缓存、消息队列或新的第三方 HTTP 客户端；
- 不将 TMDB 影片元数据本身持久化到数据库（仍走现有 Caffeine 5 分钟解析缓存）；
- 不将任何 `/api/netmirror/**` 接口加入 `WebConfig` 免登录白名单。

---

## 2. 组件拓扑与职责边界

~~~mermaid
flowchart TD
    Ctrl["NetMirrorConfigController<br/>/api/netmirror/tmdb-keys"] -->|状态/刷新/覆盖| Svc["NetMirrorTmdbKeyService"]
    Parser["NetMirrorParser<br/>fetchTmdbMeta()"] -->|getKeys / refreshOnInvalid| Svc
    Svc -->|读/写快照| Cache["volatile KeySnapshot<br/>进程内内存快照"]
    Svc -->|findByKey / insert / update| Mapper["ParserRuntimeConfigMapper<br/>(XML SQL)"]
    Mapper --> DB[("SQLite / MySQL<br/>parser_runtime_config")]
    Svc -->|纯 JDK HttpClient<br/>抓取首页与 JS| Site["NetMirror 官网<br/>(netmirror.gold)"]
    Svc -->|纯 JDK HttpClient<br/>/3/configuration 验活| TMDB["tmdb.imdb3.shop/3"]
    Parser -->|纯 JDK HttpClient<br/>/{type}/{id}| TMDB
~~~

### 文件与包路径清单

~~~text
backend/src/main/resources/
  db/migration/sqlite/V28__parser_runtime_config.sql          （新增）
  db/migration/mysql/V28__parser_runtime_config.sql           （新增）
  mapper/ParserRuntimeConfigMapper.xml                        （新增）
  application.yml                                             （修改：新增 app.netmirror.* 占位符）
backend/src/main/java/cc/ivera/parser/
  domain/ParserRuntimeConfig.java                             （新增）
  domain/ParserRuntimeConfigMapper.java                       （新增）
  application/parser/video/NetMirrorTmdbKeyService.java       （新增）
  application/parser/video/NetMirrorParser.java               （修改）
  interfaces/NetMirrorConfigController.java                   （新增）
.env / backend/.env / .env.example                            （同步修改：若启用环境变量配置）
~~~

### 职责边界

- `NetMirrorConfigController`：参数校验（`jakarta.validation`）、委派 `NetMirrorTmdbKeyService`、包装 `ApiResponse.ok(...)`；不含抓取、正则或持久化逻辑，不手写 try/catch 兜底。
- `NetMirrorTmdbKeyService`：TMDB Key 生命周期唯一所有者，负责内存快照维护、数据库读写、官网首页与 JS 抓取提取、验活过滤、单飞并发控制、冷却防抖与脱敏视图构造。
- `NetMirrorParser`：仅调用 `NetMirrorTmdbKeyService.getKeys()` 与 `refreshOnInvalid(failedKeys)` 完成 TMDB 元数据请求与 1 轮失效重试，不直接碰表或抓取首页 JS。
- `ParserRuntimeConfigMapper` + `ParserRuntimeConfigMapper.xml`：纯数据访问层，所有 SQL 位于 XML，不写业务决策。

---

## 3. 数据库与持久层合同

### 3.1 Flyway 迁移脚本（V28）

#### SQLite：`backend/src/main/resources/db/migration/sqlite/V28__parser_runtime_config.sql`

```sql
CREATE TABLE parser_runtime_config (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    config_key VARCHAR(128) NOT NULL,
    config_value TEXT NOT NULL,
    source VARCHAR(32) NOT NULL,
    source_url VARCHAR(512),
    last_verified_at DATETIME,
    last_refreshed_at DATETIME,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted BIGINT NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uk_parser_runtime_config_key ON parser_runtime_config (config_key, deleted);
```

#### MySQL：`backend/src/main/resources/db/migration/mysql/V28__parser_runtime_config.sql`

```sql
CREATE TABLE parser_runtime_config (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    config_key VARCHAR(128) NOT NULL COMMENT '配置键，如 netmirror.tmdb_keys',
    config_value TEXT NOT NULL COMMENT '配置值（JSON 字符串）',
    source VARCHAR(32) NOT NULL COMMENT '来源：AUTO_FETCH / MANUAL',
    source_url VARCHAR(512) NULL COMMENT '自动提取时的来源 JS URL',
    last_verified_at DATETIME NULL COMMENT '最近一次校验可用时间',
    last_refreshed_at DATETIME NULL COMMENT '最近一次抓取或覆盖时间',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted BIGINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常 非0=记录 id',
    UNIQUE KEY uk_parser_runtime_config_key (config_key, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='解析器运行态动态配置';
```

### 3.2 数据语义与实体约定

- 实体：`cc.ivera.parser.domain.ParserRuntimeConfig`
  - 注解：`@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor @TableName("parser_runtime_config")`
  - 主键：`@TableId(type = IdType.AUTO) private Long id;`
  - 逻辑删除：`@TableLogic private Long deleted;`
  - 时间字段：`LocalDateTime`（`lastVerifiedAt`、`lastRefreshedAt`、`createdAt`、`updatedAt`）
- 本功能固定 `config_key`：`"netmirror.tmdb_keys"`。
- `config_value` 存储格式：标准 JSON 字符串数组，例如：
  ```json
  ["c8dea14dc917687ac631a52620e4f7ad","3cb41ecea3bf606c56552db3d17adefd"]
  ```
- `source` 枚举值：
  - `AUTO_FETCH`：由系统自动或手动触发 `/refresh` 从 NetMirror 官网 JS 提取并验活写入；
  - `MANUAL`：通过 `PUT /api/netmirror/tmdb-keys` 手动覆盖写入。
- 归属说明：该表为**系统级共享配置**，非用户个人资产，不含 `user_id` 字段。

### 3.3 MyBatis Mapper 合同

接口 `cc.ivera.parser.domain.ParserRuntimeConfigMapper extends BaseMapper<ParserRuntimeConfig>`（由 `@MapperScan("cc.ivera.**.domain")` 自动扫描）：

- `ParserRuntimeConfig findByKey(@Param("configKey") String configKey);`
  - `SELECT * FROM parser_runtime_config WHERE config_key = #{configKey} AND deleted = 0 LIMIT 1`
- `int insertConfig(@Param("c") ParserRuntimeConfig config);`
  - 插入完整记录，`deleted` 固定写入 `0`，`useGeneratedKeys="true" keyProperty="c.id"`。
- `int updateConfig(@Param("c") ParserRuntimeConfig config);`
  - `UPDATE parser_runtime_config SET config_value = #{c.configValue}, source = #{c.source}, source_url = #{c.sourceUrl}, last_verified_at = #{c.lastVerifiedAt}, last_refreshed_at = #{c.lastRefreshedAt}, updated_at = #{c.updatedAt} WHERE id = #{c.id} AND deleted = 0`
- `int logicalDeleteById(@Param("id") Long id);`
  - `UPDATE parser_runtime_config SET deleted = id WHERE id = #{id} AND deleted = 0`（遵循项目 `deleted = id` 逻辑删除规范）。

---

## 4. 配置项规格

在 `backend/src/main/resources/application.yml` 中增加：

```yaml
app:
  netmirror:
    site-origin: ${NETMIRROR_SITE_ORIGIN:https://netmirror.gold}
    tmdb-refresh-cooldown-seconds: ${NETMIRROR_TMDB_REFRESH_COOLDOWN_SECONDS:60}
```

配置约束：
- 若同步加入环境变量，必须同时更新仓库根 `.env`、`backend/.env`、`.env.example` 三处，并核对三处变量名集合完全一致；
- `site-origin` 加载后自动做归一化处理：去除首尾空白与末尾所有 `/`（确保形如 `https://netmirror.gold`），并由 `NetMirrorParser` 与 `NetMirrorTmdbKeyService` 共享，保证今后换域名只需改配置重启；
- `tmdb-refresh-cooldown-seconds` 最小保护下限为 `10` 秒（若配置小于 10 则按 10 处理，防止配置 0 导致异常时疯狂刷官网）。

---

## 5. 核心算法与状态机合同

### 5.1 内存快照与并发控制

```java
private record KeySnapshot(
        List<String> keys,
        String source,
        String sourceUrl,
        LocalDateTime lastVerifiedAt,
        LocalDateTime lastRefreshedAt
) {}
```

- `private volatile KeySnapshot cachedSnapshot;`
- `private final ReentrantLock refreshLock = new ReentrantLock();`
- `private volatile Instant lastAutoRefreshAttempt;`（记录最近一次自动抓取尝试时间，用于冷却防抖；即使抓取失败也计入冷却，防止官网短暂故障期间每个请求都重复下载 780KB JS）。

### 5.2 冷启动读取流程：`getKeys()`

1. 读取 `KeySnapshot snap = cachedSnapshot;`。若 `snap != null && !snap.keys().isEmpty()`，直接返回 `snap.keys()`。
2. 查询数据库 `ParserRuntimeConfig row = mapper.findByKey(CONFIG_KEY);`。
3. 若 `row != null`，反序列化 `row.getConfigValue()` 为 `List<String>` 并过滤合法 32 位 hex；若非空，构建 `KeySnapshot` 写入 `cachedSnapshot` 并返回。
4. 若数据库无记录或解析为空列表，调用 `refreshFromSite(false)`（内部加 `refreshLock` 并执行双检：进锁后再次查 `cachedSnapshot` 与数据库，避免并发冷启动重复拉取）。

### 5.3 官网抓取与抗混淆提取算法：`fetchKeysFromSite()`

使用纯 JDK `HttpClient`（沿用 `app.parse-timeout` 与 `app.proxy`、`followRedirects(NORMAL)`、`UA` 头）：

1. **抓取入口页**：
   - `GET {siteOrigin}/`；非 HTTP 200 抛 `BusinessException("NetMirror 首页请求失败，HTTP " + statusCode)`。
2. **定位入口 JS bundle**：
   - 依次匹配以下正则提取脚本 `src`：
     - 优先：`<script\b[^>]*\btype=["']module["'][^>]*\bsrc=["']([^"']+\.js(?:\?[^"']*)?)["']`
     - 反向属性序：`<script\b[^>]*\bsrc=["']([^"']+\.js(?:\?[^"']*)?)["'][^>]*\btype=["']module["']`
     - 兜底：`src=["'](/assets/index-[^"']+\.js(?:\?[^"']*)?)["']`
   - 用 `URI.create(siteOrigin + "/").resolve(scriptSrc)` 解析为绝对 URI `jsUri`。
   - **安全校验**：`jsUri.getScheme()` 必须为 `http` 或 `https`，且 `jsUri.getHost()` 必须与 `siteOrigin` 的 host 相同（大小写无关），防止页面注入异常外链触发 SSRF。
3. **下载 JS bundle**：
   - `GET {jsUri}`（带 `Referer: {siteOrigin}/`），响应体流式读取并限制最大字节数 `5 * 1024 * 1024`（5 MB），超限直接拒绝。
4. **提取候选 32 位十六进制 TMDB Keys（不依赖混淆变量名）**：
   - 统一 Key 格式校验正则：`^[0-9a-fA-F]{32}$`；
   - **策略 A（上下文窗口锚定）**：
     - 在 JS 文本中查找 `"tmdb.imdb3.shop"`（支持出现多处时遍历）；
     - 截取每处前 `1500` 字符至后 `500` 字符的窗口；
     - 在窗口内匹配字符串数组字面量：`\[(?:\s*["']([0-9a-fA-F]{32})["']\s*,?)+\]`（对匹配到的数组片段再提取全部 32 位 hex 元素），使用 `LinkedHashSet` 保序去重；
     - 若策略 A 提取到 ≥ 1 个候选 Key，进入验活阶段。
   - **策略 B（全文兜底扫描）**：
     - 仅当策略 A 未提取到任何候选或候选全部未通过验活时启用；
     - 在全文扫描包含至少 2 个 32 位 hex 字符串的数组字面量 `\[(?:\s*["'][0-9a-fA-F]{32}["']\s*,\s*){1,}["'][0-9a-fA-F]{32}["']\s*\]`，汇总去重（最多取前 10 个候选防滥用），进入验活阶段。
5. **验活过滤**：
   - 对每个候选 Key 发起轻量探测：`GET https://tmdb.imdb3.shop/3/configuration?api_key={key}`（超时取 `Math.min(parseTimeout, 15)` 秒）；
   - 状态码判定：
     - `200`：明确有效，加入 `validKeys`；
     - `429`：临时限流，说明鉴权已通过、Key 本身有效，加入 `validKeys`；
     - `401` / `403`：明确失效，剔除并记录脱敏日志；
     - 其他 `5xx` 或网络异常：记录日志，不判为有效。
   - 若 `validKeys.isEmpty()`：
     - **禁止覆写数据库**；
     - 抛出 `BusinessException("从 NetMirror 站点提取有效 TMDB Key 失败，请稍后重试或通过管理接口手动配置")`。

### 5.4 覆盖落库规则：`persistKeys(...)`

必须在 `refreshLock` 持有期间执行：

1. 将 `validKeys` 序列化为 JSON 数组字符串 `configValue`。
2. 查 `ParserRuntimeConfig existing = mapper.findByKey(CONFIG_KEY);`。
3. 记当前时间 `now = LocalDateTime.now()`：
   - 若 `existing == null`：构造新对象（`deleted = 0L`、`createdAt = now`、`updatedAt = now`、`lastRefreshedAt = now`、`lastVerifiedAt = verified ? now : null`），调用 `mapper.insertConfig(...)`；
   - 若 `existing != null`：就地覆盖 `configValue`、`source`、`sourceUrl`、`lastRefreshedAt = now`、`lastVerifiedAt = verified ? now : existing.getLastVerifiedAt()`、`updatedAt = now`，调用 `mapper.updateConfig(...)`。
4. 同步更新 `cachedSnapshot`，确保后续请求立即可见最新配置。

### 5.5 失效触发与自动覆盖：`NetMirrorParser.fetchTmdbMeta` + `refreshOnInvalid`

#### 状态码分流矩阵

| `GET tmdb.imdb3.shop/3/{type}/{id}` 响应 | 当前轮行为 | 是否触发失效刷新 |
|---|---|---|
| `HTTP 200` | 解析并返回 JSON | 否 |
| `HTTP 401` / `403` | 置 `authFailed = true`，继续尝试列表下一把 Key | 全部 Key 失败后：**是** |
| `HTTP 429` | 记录限流状态，继续尝试列表下一把 Key | 若本轮无任何 `401/403`：**否**（直接提示限流/稍后重试） |
| `HTTP 404` | 直接抛出 `BusinessException("NetMirror TMDB 未找到对应影片元数据")` | **否**（ID 不存在，换 Key 无效） |
| `HTTP 5xx` / 网络超时 | 记录最后异常，继续尝试列表下一把 Key | 若本轮无任何 `401/403`：**否** |

#### 自动刷新覆盖重试逻辑（最多重试 1 轮）

1. 第一轮取 `List<String> keys = tmdbKeyService.getKeys();`，依次请求。
2. 若第一轮全部失败且 `authFailed == true`：
   - 调用 `List<String> refreshedKeys = tmdbKeyService.refreshOnInvalid(keys);`；
   - `refreshOnInvalid(failedKeys)` 内部行为：
     1. 获取 `refreshLock`；
     2. 双检：若 `cachedSnapshot != null && !cachedSnapshot.keys().equals(failedKeys)`，说明其他并发请求已完成刷新，直接返回 `cachedSnapshot.keys()`；
     3. 冷却检查：若距离 `lastAutoRefreshAttempt` 未满 `cooldownSeconds`，抛出 `BusinessException("NetMirror TMDB Key 已失效且刷新处于冷却期，请稍后重试或手动刷新")`；
     4. 记录 `lastAutoRefreshAttempt = Instant.now()`，调用官网抓取 + 验活 + 覆盖落库，返回新 `List<String>`。
   - 用 `refreshedKeys` 执行第二轮请求（**仅 1 次重试**）；若仍失败，抛出中文 `BusinessException`。

---

## 6. HTTP 接口合同

Controller：`cc.ivera.parser.interfaces.NetMirrorConfigController`
路由前缀：`/api/netmirror/tmdb-keys`（强制 JWT 登录，**不加白名单**，未登录返回 `401`）。

### 6.1 脱敏规则

```java
static String maskKey(String key) {
    if (key == null || key.length() < 8) {
        return "****";
    }
    return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
}
```

### 6.2 `GET /api/netmirror/tmdb-keys` — 查询当前状态

- 行为：读取数据库记录（若 `cachedSnapshot` 未初始化则加载数据库，**不触发**官网抓取，保持 `GET` 无副作用且幂等）。
- 响应 `200 OK`（已配置示例）：

```json
{
  "success": true,
  "data": {
    "configured": true,
    "count": 2,
    "maskedKeys": ["c8de****f7ad", "3cb4****defd"],
    "source": "AUTO_FETCH",
    "sourceUrl": "https://netmirror.gold/assets/index-17d36580.js",
    "lastVerifiedAt": "2026-11-24T19:10:00",
    "lastRefreshedAt": "2026-11-24T19:10:00"
  }
}
```

- 响应 `200 OK`（数据库尚无记录示例）：

```json
{
  "success": true,
  "data": {
    "configured": false,
    "count": 0,
    "maskedKeys": [],
    "source": null,
    "sourceUrl": null,
    "lastVerifiedAt": null,
    "lastRefreshedAt": null
  }
}
```

### 6.3 `POST /api/netmirror/tmdb-keys/refresh` — 手动立即抓取并覆盖

- 行为：在 `refreshLock` 保护下**绕过自动刷新冷却**，立即抓取 NetMirror 官网首页 → JS bundle → 提取候选 Key → 验活 → 覆盖数据库与内存快照。
- 响应 `200 OK`：返回同 `6.2` 结构的最新脱敏状态对象（`source = "AUTO_FETCH"`）。
- 失败响应 `400`（`BusinessException`）：例如官网不可达或未提取到有效 Key，原有库记录保持不变。

### 6.4 `PUT /api/netmirror/tmdb-keys` — 手动覆盖自定义 Key 列表

- 请求体（DTO 使用 `jakarta.validation` 校验）：

```json
{
  "keys": [
    "c8dea14dc917687ac631a52620e4f7ad"
  ],
  "verify": true
}
```

- 参数校验规则：
  - `keys`：`@NotEmpty(message = "TMDB Key 列表不能为空")`，最多允许 10 个元素；每项去除首尾空白后必须匹配 `^[0-9a-fA-F]{32}$`，自动保序去重；
  - `verify`：`Boolean`，可省略，默认 `true`。
- 行为：
  - 当 `verify = true` 时，调用 `/3/configuration` 验活：若存在无效 Key（`401`/`403`）或全部未能通过验活，抛出明确中文 `BusinessException` 并**拒绝覆盖**；
  - 当 `verify = false` 时，跳过远端验活直接覆盖落库（便于离线配置或联调测试失效自动覆盖流程）；
  - 写入数据库，标记 `source = "MANUAL"`、`sourceUrl = null`，更新内存快照。
- 响应 `200 OK`：返回同 `6.2` 结构的最新脱敏状态对象。

---

## 7. 安全与日志约束

1. **禁止明文泄露**：
   - Controller 任何接口都不回传完整 TMDB Key 明文；
   - `NetMirrorParser` 与 `NetMirrorTmdbKeyService` 的全部日志（`info`/`warn`/`error`）均须使用 `maskKey(key)`，禁止把完整 `?api_key=...` URL 直接打印进异常栈或日志。
2. **禁止裸白名单**：`/api/netmirror/tmdb-keys/**` 全部受 `JwtAuthInterceptor` 保护。
3. **SSRF 防护**：官网抓取仅允许访问配置好的 `siteOrigin` 及其同 host 的 JS 脚本 URI，且限制单次 JS 下载体量 ≤ 5 MB。
4. **编码约束**：所有新增与修改的文件（Java / XML / SQL / yml / md）必须为**无 BOM 的 UTF-8**。

---

## 8. 验收测试矩阵（实施后执行）

| 编号 | 测试场景 | 操作步骤 | 预期结果 |
|---|---|---|---|
| T1 | 编译与迁移 | 执行 `mvn -q clean compile -DskipTests` 并启动后端 `8082` | 编译 Exit Code 0；Flyway `V28` 成功建表 `parser_runtime_config` |
| T2 | 未登录鉴权 | 不带 Token 请求 `GET/POST/PUT /api/netmirror/tmdb-keys` | 全部返回 HTTP `401` |
| T3 | 空库只读查询 | 登录后调用 `GET /api/netmirror/tmdb-keys` | 返回 `configured=false, count=0`，不触发外网抓取 |
| T4 | 空库冷启动自动获取保存 | 登录后解析 `https://netmirror.gold/tmdb/watch/movie/550`（或有 MP4 源的 TMDB 链接） | 自动拉取 `netmirror.gold` 首页与 `index-*.js`，提取并验活 2 个 Key 写入 DB；`GET /api/netmirror/tmdb-keys` 显示 `configured=true, count=2, source=AUTO_FETCH` 与脱敏 Key |
| T5 | 失效自动检测与覆盖 | 调用 `PUT /api/netmirror/tmdb-keys` 写入 `{"keys":["00000000000000000000000000000000"],"verify":false}`；随后触发解析 `/tmdb/watch/...` | 日志记录脱敏假 Key 命中 `401` → 触发单飞刷新 → 从官网拉取真 Key 验活覆盖 DB → 自动重试解析成功；再次 `GET` 显示 `source=AUTO_FETCH` 且已恢复真实脱敏 Key |
| T6 | 手动刷新覆盖 | 调用 `POST /api/netmirror/tmdb-keys/refresh` | 绕过冷却立即抓取并覆盖，`lastRefreshedAt` 更新，返回最新脱敏状态 |
| T7 | 手动录入验活拦截 | 调用 `PUT /api/netmirror/tmdb-keys` 传入无效 Key 且 `verify=true` | 返回 `400` 明确中文错误，数据库中既有有效 Key 不被覆盖 |
| T8 | 404 不误刷新 | 解析不存在的 TMDB ID（如 `999999999`） | 命中 `HTTP 404` 直接返回中文业务提示，不触发刷新覆盖 |
| T9 | 文件编码检查 | 对全部改动/新增文件执行 `Format-Hex` 检查首三字节 | 均无 UTF-8 BOM (`EF BB BF`) |
