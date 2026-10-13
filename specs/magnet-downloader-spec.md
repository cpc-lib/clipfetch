# clipfetch 磁力解析与下载功能——实施方案

> 版本：v2.0（已结合 clipfetch 项目现有架构调整）  
> 平台：Windows 10/11（服务端随项目部署，浏览器访问）  
> 定位：clipfetch 现有 Web 项目内的**磁力下载功能模块**，复用本机/局域网已安装的 qBittorrent 作为下载引擎。  
> 原则：功能最少、依赖最少、可稳定使用；不自行实现 BitTorrent 协议；不引入任何新中间件。

## 1. 建设目标

在 clipfetch 中新增磁力下载功能，支持以下操作：

1. 粘贴 Magnet 磁力链接并解析元数据。
2. 查看解析出的文件名、目录、大小和文件总数。
3. 在现有数据库（SQLite/MySQL，随 .env 配置）中保存解析历史，可重新打开历史文件清单。
4. 勾选需要下载的文件，不必下载整个种子。
5. 保存目录由 `backend/.env` 统一配置（qBittorrent 可见路径），前端不传任意路径。
6. 创建、开始、暂停、继续下载任务。
7. 删除任务时默认保留已下载文件，仅当用户勾选「同时删除文件」才清理磁盘文件。
8. 显示下载百分比、实时下载速度、已下载大小、剩余时间和任务状态。
9. 刷新或重新登录后，以 qBittorrent 当前状态为准继续展示未完成下载（前端轮询时按需回写的 lazy sync）。

**不开发**磁力搜索、资源索引站、视频在线播放、视频转码等无关能力；**不引入** Redis、RabbitMQ、Kafka、WebSocket、SSE、定时任务、Electron 独立桌面壳。

## 2. 技术栈（全部复用项目现有设施）

| 层次 | 组件 | 职责 |
|---|---|---|
| 前端界面 | Vue 3 + Vite + Tailwind CSS（项目现有） | 磁力解析、历史、下载管理界面 |
| 后端 | Spring Boot 3 / Java 21（项目现有，`SERVER_PORT=8082`） | REST API、解析历史、qBittorrent 封装 |
| 限界上下文 | `cc.ivera.magnet`（新增，同 preparse 范式） | domain（实体+Mapper）+ interfaces（Controller） |
| 数据库 | SQLite / MySQL（`backend/.env` 的 `DB_URL` 可切换） | 仅保存磁力解析历史（Flyway V9 双库迁移脚本） |
| ORM | MyBatis-Plus（项目现有） | `@TableLogic deleted=id` 逻辑删除、`user_id` 数据隔离 |
| 下载引擎 | 本机/局域网 qBittorrent 5.2+ | 元数据获取、选文件、下载、暂停、续传、文件写入 |
| 引擎调用 | 纯 JDK HttpClient + CookieManager（参照 SubtitleGateway 范式） | Web API 登录、SID 会话管理、403 自动重登一次 |
| 任务进度刷新 | HTTP 轮询（前端每秒 GET，lazy sync 回写） | 刷新状态、速度、百分比、剩余时间 |

### 2.1 架构

```text
┌─────────────────────────────────────────────┐
│ 浏览器（clipfetch 现有前端）                 │
│ Vue 3 + Tailwind                            │
│  - MagnetDownloader.vue 功能组件            │
│  - api/magnet.js（axios + request.js 封装） │
└───────────────────┬─────────────────────────┘
                    │ HTTP /api/magnet/*（JWT 鉴权）
                    ▼
┌─────────────────────────────────────────────┐
│ clipfetch 后端 Spring Boot 3                │
│  - cc.ivera.magnet.interfaces.MagnetController │
│  - cc.ivera.magnet.domain.MagnetHistory(+Mapper) │
│  - QBittorrentClient（纯 JDK HttpClient）   │
│  - magnet_history 解析历史表                │
└───────────────────┬─────────────────────────┘
                    │ qBittorrent Web API
                    ▼
┌─────────────────────────────────────────────┐
│ qBittorrent 5.2+（本机安装或 Docker 容器）  │
│  - BT 元数据解析                             │
│  - 下载任务与断点续传                        │
│  - 分块校验与文件写入                        │
└───────────────────┬─────────────────────────┘
                    ▼
        .env 配置的保存路径（qBittorrent 容器内/本机可见路径）
```

**状态归属**：qBittorrent 是下载任务状态的唯一事实来源；数据库不重复维护下载任务进度或 BT 断点信息，不新增定时任务，前端 GET 轮询时按需回写。

## 3. 前置配置（backend/.env）

```properties
# ===== 磁力下载（qBittorrent）=====
# qBittorrent Web UI 地址（含端口），Web UI 需在 qBittorrent 选项中启用
qbt-host=http://127.0.0.1:8085
qbt-user=admin
qbt-pass=cc.ivera
# 保存路径：必须是 qBittorrent 进程可见的路径；qBittorrent 跑在 Docker 中时
# 必须填容器内部路径，不得传宿主机 Windows 绝对路径
QBT_SAVEPATH=/downloads
# 元数据获取超时（秒）
metadata-timeout=60
```

1. qBittorrent → 工具 → 选项 → Web UI，启用并配置用户名和强密码；建议仅监听 `127.0.0.1` 或内网，不对公网开放。
2. Web UI 端口注意错开：后端 `SERVER_PORT=8082`、字幕转换服务 RAG 占用 `8080`，qBittorrent 示例用 `8085`。
3. 连接参数统一从 `backend/.env` 读取，不写入代码与前端；前端提供「测试连接」按钮调用后端健康检查接口。
4. Docker 部署 qBittorrent 时，`QBT_SAVEPATH` 必须使用容器内部可见路径，避免生成无效路径导致种子状态错误、元数据无法获取。

## 4. 主要业务流程

### 4.1 解析磁力链接

```text
用户粘贴 Magnet 链接
    ↓
后端校验 Magnet URI、识别 Info Hash（JWT 鉴权，按 user_id 隔离）
    ↓
查询 magnet_history：同用户同 Info Hash 已成功解析的可直接展示
    ↓（无缓存）
调用 qBittorrent 添加种子（启动态，不开始下载）
    ↓
轮询 has_metadata（60 秒超时）
    ↓
获取文件列表 → 暂停该种子 → 释放任务
    ↓
得到名称、总大小及文件树，保存 magnet_history，返回前端
```

- 不使用 qBittorrent 独立的 `torrents/fetchMetadata` 接口（避免产生多余任务记录的方案已验证被 addTorrent 轮询流程取代）。
- 解析与下载解耦：解析成功不等于创建正式下载任务，元数据获取完成后即暂停种子。

**已验证的对接细节**：

| 场景 | 处理 |
|---|---|
| 登录接口返回 HTTP 204 | 本地回环免认证，与 200 + `Ok.` 一并视为登录成功 |
| `/api/v2/app/webapiVersion` | 返回纯文本版本号，健康检查直接读文本，不做 JSON 解析 |
| qBittorrent 5.x `addTorrent` | 返回 JSON 格式响应，兼容 5.x JSON 与旧版纯文本 `Ok.` 两种判断 |
| 请求返回 403 | 会话失效，自动重新登录后重试一次 |
| 异常场景 | 无可用 Peer、元数据超时、格式错误、引擎未启动、认证失败；界面显示明确状态并允许重试 |

### 4.2 选择文件并下载

```text
展示文件树和大小
    ↓
用户勾选一个或多个文件
    ↓
后端调用 qBittorrent 创建任务，并在创建时设置文件优先级
    ↓
开始下载（保存路径为 .env 配置的 QBT_SAVEPATH）
```

- 被选文件：优先级为可下载值（例如 `1`）；未选文件：优先级 `0`（跳过）。
- 尽可能在**创建任务时**就设置文件优先级，防止短暂下载不需要的文件。
- 因 BitTorrent 分块边界与文件共享等特性，可能仍出现少量额外占位/边界数据。
- qBittorrent 不支持在同一实例为**相同 Info Hash** 创建两份独立任务；重复添加时提示用户打开已有任务或调整选择。
- **前端不传保存路径**：保存目录完全由 `.env` 的 `QBT_SAVEPATH` 决定，杜绝任意路径写入。

### 4.3 管理下载

- **开始/继续**：调用 qBittorrent 的 Resume 类接口。
- **暂停**：调用 Pause/Stop 类接口。
- **仅删除任务**：通知 qBittorrent 删除种子任务，保留下载文件（默认行为）。
- **删除任务及文件**：前端二次确认且用户勾选「同时删除文件」后，才请求 qBittorrent 删除下载数据。
- **断点续传**：完全交给 qBittorrent 的持久化状态和哈希校验处理。
- **刷新/重新登录**：GET 轮询任务列表时与 qBittorrent 实际状态做 lazy sync 按需回写，无需重建任务。

## 5. REST API（前缀 `/api/magnet`，JWT 鉴权，统一 `ApiResponse{success,data,error}`）

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/api/magnet/parse` | 提交磁力解析；返回解析 ID 和解析状态 |
| `GET` | `/api/magnet/parse/{id}` | 查询解析状态及完整文件列表 |
| `GET` | `/api/magnet/history` | 按 `user_id` 隔离、时间倒序分页查询解析历史 |
| `DELETE` | `/api/magnet/history/{id}` | 逻辑删除历史（`deleted=id`），不影响实际下载 |
| `POST` | `/api/magnet/downloads` | 传入解析 ID、所选文件索引，创建下载任务 |
| `GET` | `/api/magnet/downloads` | 返回 qBittorrent 当前任务及实时进度（lazy sync） |
| `POST` | `/api/magnet/downloads/{hash}/start` | 开始/继续 |
| `POST` | `/api/magnet/downloads/{hash}/pause` | 暂停 |
| `DELETE` | `/api/magnet/downloads/{hash}?deleteFiles=false` | 删除任务，默认保留文件 |
| `GET` | `/api/magnet/engine/health` | 检查 qBittorrent 连通性及版本（纯文本版本号） |

除登录注册外的所有接口已在项目全局拦截器中强制登录，磁力接口无需额外放行。

### 5.1 创建下载请求示例

```json
{
  "parseId": 123,
  "selectedFileIndexes": [0, 2]
}
```

后端需验证：解析记录属于当前用户（`id + user_id` 校验）、文件索引来源于已解析文件列表；保存路径取 `.env` 配置，不接受前端传入路径。

### 5.2 进度响应示例

```json
{
  "success": true,
  "data": {
    "infoHash": "example-hash",
    "name": "ubuntu-desktop.iso",
    "status": "DOWNLOADING",
    "progressPercent": 62.0,
    "downloadedBytes": 2791728742,
    "totalBytes": 4500000000,
    "downloadSpeedBytesPerSec": 12582912,
    "etaSeconds": 136,
    "savePath": "/downloads/Ubuntu"
  }
}
```

- 百分比采用 `0~100` 格式对外输出；速度以 `B/s` 传输，前端格式化为 `KB/s`、`MB/s`。
- 前端每 **1 秒**轮询一次 `/api/magnet/downloads`；页面不可见时可降低频率。
- 状态和数据以 qBittorrent 实际响应为准，不伪造速度或剩余时间。

## 6. 数据库：一张历史表（Flyway V9，SQLite/MySQL 双脚本）

V8 已被 `temp_user_id` 占用，新迁移使用 **V9__magnet_history.sql**，分别放入
`db/migration/sqlite/` 与 `db/migration/mysql/`，保持双库一致。

```sql
CREATE TABLE magnet_history (
    id           BIGINT PRIMARY KEY AUTOINCREMENT,  -- MySQL 为 BIGINT AUTO_INCREMENT
    user_id      BIGINT NOT NULL,
    info_hash    VARCHAR(64) NOT NULL,
    magnet_uri   TEXT NOT NULL,
    name         VARCHAR(512),
    total_size   BIGINT,
    files_json   TEXT,
    status       VARCHAR(32) NOT NULL,
    deleted      BIGINT NOT NULL DEFAULT 0,
    created_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uk_magnet_history
    ON magnet_history(user_id, info_hash, deleted);
```

- `files_json` 保存文件索引、相对路径、文件大小；更新元数据时维护 `updated_at`。
- 逻辑删除遵循项目既有约定：`@TableLogic` + 删除时执行 `UPDATE magnet_history SET deleted = id`，保证唯一索引不冲突且 `deleted = 0` 过滤生效（与 pre_parse 同一处理方式，自定义 SQL 写在 XML 映射中，禁止注解 SQL）。
- 所有查询强制 `user_id` 隔离，避免不同用户历史互相可见。
- **无需下载任务表**：任务增删改查与断点记录全部交给 qBittorrent。

## 7. 前端：一个功能组件（项目现有导航接入）

### 7.1 磁力下载组件（`src/components/MagnetDownloader.vue`）

- Magnet URL 输入框、解析按钮、解析进度/状态与错误信息。
- 文件树展示（名称、大小、路径）及勾选框。
- 「开始下载」按钮（保存路径由服务端配置，界面展示当前保存目录）。
- 下载管理：名称、进度条、百分比、下载速度、已下载量、剩余时间、状态、保存路径。
- 操作：开始、暂停、继续、删除；删除时默认保留文件，勾选「同时删除文件」需二次确认。
- 解析历史：名称、总大小、解析时间、状态；重新打开文件列表再次勾选下载；删除单条历史（逻辑删除）；该种子已存在下载任务时提示并定位到下载管理。
- qBittorrent 离线时显示连接错误和重试入口（复用「测试连接」健康检查）。

### 7.2 接入方式

- 新增 `src/api/magnet.js`，复用 `src/api/request.js` 的 axios 封装（自动携带 JWT、统一错误处理）。
- `src/stores/app.js` 的 `appView.active` 增加导航项（如 `magnet`），`App.vue` 中按现有 `v-if` 模式挂载组件，`AppHeader` 增加入口。

## 8. 新增/改动文件清单（项目内）

```text
clipfetch/
├── backend/
│   ├── .env                                          # 追加 qbt-* 配置项
│   └── src/main/
│       ├── java/cc/ivera/magnet/
│       │   ├── domain/
│       │   │   ├── MagnetHistory.java                # 实体，@TableLogic deleted
│       │   │   └── MagnetHistoryMapper.java          # Mapper（XML：删除/按 user_id 查询）
│       │   └── interfaces/
│       │       └── MagnetController.java             # /api/magnet/* 全部端点
│       ├── resources/
│       │   ├── mapper/MagnetHistoryMapper.xml        # 自定义 SQL（逻辑删除等）
│       │   └── db/migration/
│       │       ├── sqlite/V9__magnet_history.sql
│       │       └── mysql/V9__magnet_history.sql
│       └── （QBittorrentClient 放置在 magnet 上下文内，纯 JDK HttpClient）
└── frontend/src/
    ├── api/magnet.js                                 # 接口封装
    ├── components/MagnetDownloader.vue               # 功能组件
    ├── stores/app.js                                 # 追加 magnet 导航项
    └── components/AppHeader.vue                      # 追加入口按钮
```

## 9. 安全和稳定性底线

1. qBittorrent Web UI 仅监听回环地址或内网，不暴露公网；连接凭据只存在于 `backend/.env`，不写入日志、数据库和前端。
2. 所有接口走项目 JWT 鉴权，数据按 `user_id` 隔离，解析记录归属按 `id + user_id` 校验，防止越权。
3. 保存路径仅由 `.env` 配置，前端不可传任意路径；文件索引需校验来源于已解析列表。
4. 对磁力 URI、文件索引、删除操作进行输入校验；历史删除为逻辑删除。
5. 不自动打开或执行下载的文件。
6. 网络无 Peer、磁盘空间不足、qBittorrent 退出或重启时，清晰提示并允许重试。
7. 下载状态不可由数据库伪造；刷新后必须以 qBittorrent 当前状态 lazy sync。

## 10. 分阶段实施与验收

| 阶段 | 实现内容 | 验收标准 |
|---|---|---|
| P1 联通与解析 | .env 配置、QBittorrentClient、V9 迁移、解析端点 | 登录后可解析合法测试 Magnet 并显示文件列表；失败有明确提示；历史入库且按用户隔离 |
| P2 文件选择与下载 | 文件勾选、优先级设置、任务控制 | 仅勾选指定文件被下载，正确保存到 `.env` 配置目录，支持开始/暂停/继续/删除 |
| P3 进度与恢复 | 每秒轮询、lazy sync、重启恢复 | 速度/百分比准确；刷新页面、重启后端后任务状态与 qBittorrent 一致 |
| P4 收尾验收 | 前端组件接入导航、双库验证 | SQLite 与 MySQL 均可建表运行；不新增任何中间件依赖 |

测试文件建议使用有明确授权的公开种子，例如 Linux 发行版官方提供的种子。

## 11. 最终交付要求

- 后端 `cc.ivera.magnet` 限界上下文源代码（domain + interfaces）。
- Flyway V9 双库迁移脚本，SQLite 自动建表，MySQL 可切换。
- 前端 `MagnetDownloader.vue` 组件与导航接入。
- `backend/.env` 追加 qBittorrent 配置项及注释说明。
- 完成上述功能与异常场景的基本测试。

**最终结论：** 不做独立桌面程序，磁力下载作为 clipfetch 的功能模块落地——复用现有 Vue 前端、Spring Boot 后端、SQLite/MySQL 双库与 JWT 鉴权；qBittorrent 作为唯一下载引擎与状态唯一事实来源；数据库只存解析历史；进度走前端轮询 lazy sync。该方案无需引入 Electron、Redis、消息队列、WebSocket、SSE 或定时任务等任何新依赖。
