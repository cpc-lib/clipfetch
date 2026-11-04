# ClipFetch Native v2 Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Merge Magnet's C++ libtorrent core into ClipFetch as a MySQL-only, Redis-gated, restart-safe Native v2 engine using ClipFetch authentication.

**Architecture:** A root Maven aggregator builds six focused v2 modules and the existing `backend`, which remains the only Spring Boot API. MySQL drives parse/task/session state, Redis only enforces short-lived quotas, and a single C++ libtorrent Worker executes gRPC commands against a shared storage root. qBittorrent and Java Native v1 remain unchanged.

**Tech Stack:** Java 21, Spring Boot 3.5.4, MyBatis-Plus/XML, MySQL 8.4, Redis 7, gRPC/protobuf, C++20, libtorrent 2.0.8, Vue 3/Vite 6/Tailwind, JUnit 5, Testcontainers, Vitest, Playwright, Docker Compose.

**Execution constraint:** Project instructions prohibit proactive commits. Replace every usual commit checkpoint with `git diff --check`, a scoped diff review, and a factual `HANDOFF.md` update.

---

## File map

### Build and configuration

- Create `pom.xml`: root parent/aggregator and version management.
- Modify `backend/pom.xml`: inherit root parent and depend on the six v2 modules.
- Create `server/native-v2-*/pom.xml`: module dependency boundaries.
- Modify `.gitignore`: allow `server/**/src/test/**`, `frontend/src/**/*.test.js`, and `frontend/e2e/**`.
- Modify `backend/src/main/resources/application.yml`: `app.magnet.native-v2` and Redis-backed settings.
- Modify `.env.example` and ignored `backend/.env`: complete v2 parameters without printing secrets.

### Java modules

- `server/native-v2-domain`: entities, Mapper interfaces, torrent parsers, state machines and error codes.
- `server/native-v2-engine-spi`: Worker-independent commands, snapshots and `NativeV2Engine`.
- `server/native-v2-application`: use cases, ports, DTO projections and retry/completion policy.
- `server/native-v2-infrastructure`: file storage, Redis quotas, repository adapters and Mapper XML.
- `server/native-v2-engine-libtorrent`: generated Java protobuf code and gRPC adapter.
- `server/native-v2-scheduler`: parse/task fixed-delay dispatchers.

### Backend assembly

- Create `backend/src/main/java/cc/ivera/magnet/v2/interfaces/NativeV2Controller.java`.
- Create `backend/src/main/java/cc/ivera/magnet/v2/interfaces/NativeV2StreamController.java`.
- Create `backend/src/main/java/cc/ivera/magnet/v2/interfaces/NativeV2Dtos.java`.
- Modify `backend/src/main/java/cc/ivera/shared/config/WebConfig.java`: whitelist only the v2 ticket stream.
- Add V24 MySQL/SQLite migrations under the existing migration directories.

### Native Worker and acceptance

- Create `native/proto/download_engine_v2.proto`.
- Create `native/libtorrent-worker/`: CMake, Worker source, path/IP policy and CTest.
- Create `infra/native-v2/compose.acceptance.yml` and deterministic local-seeder/runner assets.

### Frontend

- Create `frontend/src/api/magnetV2.js`.
- Create `frontend/src/components/NativeV2TorrentManager.vue`.
- Modify `frontend/src/components/MagnetEngineWorkspace.vue`.
- Modify `frontend/package.json`; add Vitest/component and Playwright tests.

## Task 1: Establish the Maven and test skeleton

**Files:**
- Create: `pom.xml`
- Modify: `backend/pom.xml`
- Create: `server/native-v2-domain/pom.xml`
- Create: `server/native-v2-engine-spi/pom.xml`
- Create: `server/native-v2-application/pom.xml`
- Create: `server/native-v2-infrastructure/pom.xml`
- Create: `server/native-v2-engine-libtorrent/pom.xml`
- Create: `server/native-v2-scheduler/pom.xml`
- Modify: `.gitignore`

- [ ] **Step 1: Verify the missing root build fails**

Run from the repository root:

```powershell
mvn -q -N help:evaluate "-Dexpression=project.artifactId" -DforceStdout
```

Expected: exit 1 because the repository has no root `pom.xml`.

- [ ] **Step 2: Add the root parent and module order**

Create a Spring Boot 3.5.4 parent with `groupId=cc.ivera`, `artifactId=clipfetch-parent`, `version=1.0.0`, Java 21, UTF-8, gRPC 1.65.1, protobuf 4.27.1, Testcontainers 1.20.4, and these modules in dependency order:

```xml
<modules>
  <module>server/native-v2-domain</module>
  <module>server/native-v2-engine-spi</module>
  <module>server/native-v2-application</module>
  <module>server/native-v2-infrastructure</module>
  <module>server/native-v2-engine-libtorrent</module>
  <module>server/native-v2-scheduler</module>
  <module>backend</module>
</modules>
```

Manage gRPC/protobuf/Testcontainers BOMs and configure compiler release 21, Surefire 3.5.2 and Failsafe 3.5.2. Add a `native-v2-it` profile that runs `**/*IT.java` during `verify`.

- [ ] **Step 3: Convert backend and create focused module POMs**

Change `backend/pom.xml` to inherit `../pom.xml`, keep artifact `backend`, and add dependencies on all v2 modules. Preserve its current web, Playwright, database, JWT and Spring Boot plugin dependencies.

Use these module edges:

```text
native-v2-domain -> mybatis-plus-annotation, lombok, junit(test)
native-v2-engine-spi -> native-v2-domain
native-v2-application -> domain, engine-spi, spring-context, spring-tx
native-v2-infrastructure -> application, spring-data-redis, mybatis-plus starter
native-v2-engine-libtorrent -> engine-spi, grpc/protobuf
native-v2-scheduler -> application, spring-context
```

Do not add Magnet auth, Spring Security, Micrometer, aria2 or monitoring dependencies.

- [ ] **Step 4: Make new tests trackable**

Append narrowly scoped exceptions after the existing ignore rules:

```gitignore
!server/**/src/test/
!server/**/src/test/**
!frontend/src/**/*.test.js
!frontend/e2e/
!frontend/e2e/**
!native/libtorrent-worker/tests/
!native/libtorrent-worker/tests/**
```

- [ ] **Step 5: Verify the empty reactor**

Run:

```powershell
mvn -q clean compile -DskipTests
```

Expected: exit 0 and all seven modules appear in the reactor summary.

## Task 2: Port and test the torrent domain

**Files:**
- Create under `server/native-v2-domain/src/main/java/cc/ivera/magnet/v2/domain/`: `common/*`, `torrent/*`, `resource/*`, `parse/*`, `task/*`
- Test: `server/native-v2-domain/src/test/java/cc/ivera/magnet/v2/domain/torrent/MagnetParserTest.java`
- Test: `server/native-v2-domain/src/test/java/cc/ivera/magnet/v2/domain/torrent/TorrentMetaParserTest.java`
- Test: `server/native-v2-domain/src/test/java/cc/ivera/magnet/v2/domain/task/TaskStateMachineTest.java`
- Test support: `server/native-v2-domain/src/test/java/cc/ivera/magnet/v2/domain/torrent/V2TorrentFixtures.java`

- [ ] **Step 1: Add failing V1/V2/Hybrid and path tests**

Use deterministic hashes:

```java
@Test void parsesV1V2AndHybridIdentity() {
    String v1="0123456789abcdef0123456789abcdef01234567";
    String v2="1220"+"ab".repeat(32);
    assertEquals(VersionType.V1, parser.parse("magnet:?xt=urn:btih:"+v1).versionType());
    assertEquals(VersionType.V2, parser.parse("magnet:?xt=urn:btmh:"+v2).versionType());
    assertEquals(VersionType.HYBRID,
        parser.parse("magnet:?xt=urn:btih:"+v1+"&xt=urn:btmh:"+v2).versionType());
}

@Test void rejectsUnsafeRelativePaths() {
    assertThrows(V2DomainException.class,
        () -> PathSanitizer.joinRelativePath(List.of("safe","..","escape.bin")));
    assertThrows(V2DomainException.class,
        () -> PathSanitizer.joinRelativePath(List.of("CON")));
}
```

Run:

```powershell
mvn -q -pl server/native-v2-domain -Dtest=MagnetParserTest,TorrentMetaParserTest test
```

Expected: test compilation fails because the v2 domain classes do not exist.

- [ ] **Step 2: Port only the approved Magnet domain sources**

Adapt these exact Magnet sources to `cc.ivera.magnet.v2.domain`:

```text
common/Digests.java
common/Hex.java
common/ErrorCodes.java
torrent/bencode/BencodeDecoder.java
torrent/bencode/BNode.java
torrent/MagnetParser.java
torrent/ParsedMagnet.java
torrent/ParsedTorrent.java
torrent/ParsedTorrentFile.java
torrent/PathSanitizer.java
torrent/TorrentMetaParser.java
resource/VersionType.java
resource/TorrentFileInfo.java
parse/InputType.java
parse/ParseStatus.java
task/TaskStatus.java
```

Rename `DomainException` to `V2DomainException`. Reject private torrents after parsing, normalize BTMH to the 32-byte digest, reject tracker userinfo, and keep path checks Windows-safe.

- [ ] **Step 3: Add entities and explicit state policies**

Create Lombok/MyBatis-Plus entities named:

```text
MagnetV2Resource
MagnetV2ResourceHashAlias
MagnetV2ResourceFile
MagnetV2ParseJob
MagnetV2DownloadTask
MagnetV2DownloadTaskFile
MagnetV2EngineSession
MagnetV2TaskEvent
```

Each entity follows `@TableName`, `@TableId(type=IdType.AUTO)`, `@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder`, `LocalDateTime`, and `@TableLogic` only on user-owned logical-delete tables.

Add pure policies:

```java
public final class TaskTransitions {
    public static boolean canPause(TaskStatus s) {
        return s==TaskStatus.QUEUED || s==TaskStatus.STARTING ||
               s==TaskStatus.DOWNLOADING || s==TaskStatus.VERIFYING ||
               s==TaskStatus.RETRY_WAIT;
    }
    public static TaskStatus completionNext(TaskStatus current,
            boolean metadataReady, boolean recovering, boolean allVerified) {
        if (!metadataReady || recovering || !allVerified) return TaskStatus.DOWNLOADING;
        return current==TaskStatus.VERIFYING ? TaskStatus.COMPLETED : TaskStatus.VERIFYING;
    }
}
```

- [ ] **Step 4: Verify domain GREEN**

Run:

```powershell
mvn -q -pl server/native-v2-domain test
```

Expected: exit 0; tests cover V1/V2/Hybrid, private flag, unsafe paths and two-tick completion.

## Task 3: Add V24 schema, Mapper XML and ownership tests

**Files:**
- Create: `backend/src/main/resources/db/migration/mysql/V24__magnet_v2_engine.sql`
- Create: `backend/src/main/resources/db/migration/sqlite/V24__magnet_v2_engine.sql`
- Create Mapper interfaces under `server/native-v2-domain/src/main/java/cc/ivera/magnet/v2/domain/`
- Create XML files under `server/native-v2-infrastructure/src/main/resources/mapper/`
- Test: `backend/src/test/java/cc/ivera/magnet/v2/infrastructure/MagnetV2MapperIT.java`
- Test: `backend/src/test/java/cc/ivera/magnet/v2/NativeV2SqliteStartupTest.java`

- [ ] **Step 1: Write failing MySQL integration assertions**

```java
@Test void everyOwnedLookupRequiresUserAndActiveSlotIsExclusive() {
    long first=fixtures.insertTask(11L, resourceId, "QUEUED");
    assertTrue(taskMapper.findOwned(taskUuid,11L).isPresent());
    assertTrue(taskMapper.findOwned(taskUuid,12L).isEmpty());
    assertTrue(sessionMapper.claimActiveTask(resourceId, first,11L));
    assertFalse(sessionMapper.claimActiveTask(resourceId,
        fixtures.insertTask(12L,resourceId,"QUEUED"),12L));
}

@Test void logicalDeleteUsesRecordId() {
    long id=fixtures.insertParse(11L);
    assertEquals(1,parseMapper.logicalDelete(id,11L));
    assertEquals(id,fixtures.deletedValue("magnet_v2_parse_job",id));
}
```

Run:

```powershell
mvn -q -Pnative-v2-it -pl backend -am -Dit.test=MagnetV2MapperIT verify
```

Expected: fail because V24 tables and Mapper XML do not exist.

- [ ] **Step 2: Create both dialect migrations**

Create all eight approved tables with these mandatory unique constraints:

```text
resource(resource_uuid), resource(canonical_key)
hash_alias(hash_type,hash_bytes)
resource_file(resource_id,file_index)
parse_job(parse_uuid,deleted)
download_task(task_uuid,deleted)
download_task_file(task_id,file_index,deleted)
engine_session(resource_id), engine_session(engine_session_id)
task_event(task_id,sequence)
```

Add indexes for `(user_id,status,deleted)`, `(status,next_retry_at)`, resource FKs used by scans, and CAS `(id,user_id,revision,deleted)`. Do not modify V1–V23.

- [ ] **Step 3: Add Mapper interfaces and XML**

Create `MagnetV2*Mapper` interfaces in the domain package without `@Mapper` or SQL annotations. XML must define:

```java
Optional<MagnetV2DownloadTask> findOwned(String taskUuid,long userId);
int updateStatusCas(long id,long userId,long expectedRevision,String status,
                    long newRevision,String errorCode,String errorMessage);
int logicalDelete(long id,long userId);
int claimActiveTask(long resourceId,long taskId,long userId);
int clearActiveTask(long resourceId,long taskId);
List<MagnetV2DownloadTask> findDue(List<String> statuses,LocalDateTime now,int limit);
```

All user-owned statements include `user_id`; logical delete uses `SET deleted=id`.

- [ ] **Step 4: Verify both database modes**

Run the MySQL IT command again, then:

```powershell
mvn -q -pl backend -Dtest=NativeV2SqliteStartupTest test
```

Expected: both exit 0; SQLite starts with v2 disabled and the MySQL IT proves row locking/ownership.

## Task 4: Implement atomic storage, configuration and Redis quotas

**Files:**
- Create: `server/native-v2-application/src/main/java/cc/ivera/magnet/v2/application/port/NativeV2Storage.java`
- Create: `server/native-v2-application/src/main/java/cc/ivera/magnet/v2/application/port/NativeV2Quota.java`
- Create: `server/native-v2-infrastructure/src/main/java/cc/ivera/magnet/v2/infrastructure/storage/FileNativeV2Storage.java`
- Create: `server/native-v2-infrastructure/src/main/java/cc/ivera/magnet/v2/infrastructure/quota/RedisNativeV2Quota.java`
- Create: `server/native-v2-infrastructure/src/main/java/cc/ivera/magnet/v2/infrastructure/config/NativeV2Properties.java`
- Modify: `backend/src/main/resources/application.yml`
- Modify: `.env.example`
- Modify ignored: `backend/.env`
- Test: storage and quota tests in the same modules

- [ ] **Step 1: Write RED storage and quota tests**

```java
@Test void resumeWriteIsAtomicAndConfined(@TempDir Path root) {
    var storage=new FileNativeV2Storage(root);
    storage.writeResume(UUID.fromString(ID),new byte[]{1,2,3});
    assertArrayEquals(new byte[]{1,2,3},storage.readResume(UUID.fromString(ID)).orElseThrow());
    assertFalse(Files.exists(root.resolve("resume").resolve(ID+".dat.tmp")));
    assertThrows(V2DomainException.class,() -> storage.resolveResourceFile(
        UUID.fromString(ID),"../escape.bin"));
}
```

Redis IT must assert the parse counter and concurrent lease both expire and that ticket-rate failure maps to 429.

- [ ] **Step 2: Implement storage with one path authority**

`FileNativeV2Storage` owns:

```java
Path parseInput(UUID parseId);
Path metadata(UUID resourceId);
Path resourceRoot(UUID resourceId);
Path resume(UUID resourceId);
void writeAtomically(Path target, byte[] bytes);
Path resolveCompletedFile(UUID resourceId,String safeRelativePath);
```

Use sibling temp files, `FileChannel.force(true)`, close, then `ATOMIC_MOVE` with a same-filesystem fallback. Resolve completed files with `toRealPath()`, reject symlink/reparse escapes, and never accept user absolute paths.

- [ ] **Step 3: Implement Redis-only ephemeral quotas**

Use Spring Data Redis atomic increment/expire operations with keys containing only numeric user ID and quota kind. No business state is stored in Redis. Redis failures make new parse/ticket issuance unavailable but do not block task reads or pause intent.

- [ ] **Step 4: Bind every v2 runtime setting**

Add `app.magnet.native-v2` placeholders for every key in design section 13. The sole safe default is `MAGNET_V2_ENABLED=false`; when enabled, validate positive timeouts/limits and required MySQL/Redis/Worker/storage values. Add all keys with documented local values to `.env.example` and the ignored `backend/.env`, without logging secrets.

- [ ] **Step 5: Verify**

```powershell
mvn -q -pl server/native-v2-infrastructure -am test
```

Expected: exit 0 and no file is written outside the temporary root.

## Task 5: Define protobuf and Java gRPC adapter

**Files:**
- Create: `native/proto/download_engine_v2.proto`
- Create SPI records and `NativeV2Engine.java`
- Create: `server/native-v2-engine-libtorrent/src/main/java/cc/ivera/magnet/v2/engine/libtorrent/GrpcNativeV2Engine.java`
- Test: `GrpcNativeV2EngineTest.java`

- [ ] **Step 1: Add RED in-process gRPC tests**

The fake server records envelopes and returns two file counters:

```java
@Test void retryUsesStableCommandIdAndMapsFileProgress() {
    engine.addTorrent(command);
    engine.addTorrent(command);
    assertEquals(recorded.get(0).getEnvelope().getCommandId(),
                 recorded.get(1).getEnvelope().getCommandId());
    var snapshot=engine.snapshot(resourceUuid, envelope);
    assertEquals(List.of(10L,20L),snapshot.fileVerifiedBytes());
}

@Test void structuredTemporaryErrorIsRetryable() {
    server.reject(EngineErrorCode.TEMPORARY_UNAVAILABLE,"worker busy");
    assertEquals(V2FailureKind.TRANSIENT,
        assertThrows(NativeV2EngineException.class,
            () -> engine.pause(command)).kind());
}

@Test void nonLoopbackWorkerRequiresTlsMaterial() {
    var properties=NativeV2GrpcProperties.builder()
        .host("10.0.0.8").tlsEnabled(false).build();
    assertThrows(IllegalStateException.class,properties::validate);
}
```

Run and expect missing proto/classes compilation failure.

- [ ] **Step 2: Add the shared proto**

Retain Magnet RPCs and envelope fields, add `EngineErrorCode`, and make every command response carry `accepted`, `error_code`, and `error`. `GetSnapshotResponse` carries ordered `file_verified_bytes`. Configure Maven and CMake to generate from this single proto file; do not check in generated sources.

- [ ] **Step 3: Add a narrow SPI**

```java
public interface NativeV2Engine {
    NativeV2Health health();
    byte[] resolveMetadata(ResolveMetadataCommand command);
    void addTorrent(AddTorrentCommand command);
    void updateFilePriorities(UpdatePrioritiesCommand command);
    void pause(EngineCommand command);
    void resume(EngineCommand command);
    NativeV2Snapshot snapshot(EngineCommand command);
    ResumeSnapshot saveResume(EngineCommand command);
    void remove(EngineCommand command,boolean removeData);
}
```

Commands contain resource UUID, stable command ID, owner epoch 1, deadline and lease-until. The adapter applies configured RPC deadlines and message limits and never derives retryability from error text.

- [ ] **Step 4: Verify Java adapter**

```powershell
mvn -q -pl server/native-v2-engine-libtorrent -am test
```

Expected: exit 0 with in-process gRPC only; no native Worker required.

## Task 6: Port, constrain and test the C++ Worker

**Files:**
- Create: `native/Dockerfile`
- Create: `native/libtorrent-worker/CMakeLists.txt`
- Create: `native/libtorrent-worker/src/main.cpp`
- Create: `native/libtorrent-worker/src/resource_fences.h`
- Create: `native/libtorrent-worker/src/path_policy.h`
- Create: `native/libtorrent-worker/src/ip_filter_policy.h`
- Create: `native/libtorrent-worker/tests/worker_policy_test.cpp`

- [ ] **Step 1: Add a Docker CTest RED target**

Create a test target calling path/IP policy functions before those headers exist:

```cpp
assert(!safe_storage_path(root, root / "../escape"));
assert(blocked_endpoint("127.0.0.1"));
assert(blocked_endpoint("169.254.169.254"));
assert(!blocked_endpoint("1.1.1.1"));
```

Run:

```powershell
docker build --target test -t clipfetch-native-v2-worker-test native
```

Expected: non-zero C++ compilation because the policy functions are missing.

- [ ] **Step 2: Port only the libtorrent Worker core**

Adapt Magnet's `native/libtorrent-worker/src/main.cpp`, `resource_fences.h`, CMake and Dockerfile to the v2 proto. Remove multi-worker takeover orchestration but retain owner epoch/deadline/lease rejection, resume save/load, per-file verified progress and command deduplication.

Read every runtime setting from the approved environment keys. Bind gRPC only to the configured loopback/container address. Default IP filter blocks private/reserved networks; acceptance profile is the only private-net override.

When TLS is enabled, load the configured server certificate, private key and client CA, and require a verified client certificate. Plaintext is accepted only for loopback or the explicitly marked acceptance container network.

- [ ] **Step 3: Implement structured rejection and storage confinement**

Return stable proto error codes for invalid hashes, unsafe paths, busy resources, storage failure and temporary unavailability. Validate canonical storage path under the configured root before `add_torrent`; never echo the full path in responses.

- [ ] **Step 4: Verify Worker build and CTest**

```powershell
docker build --target test -t clipfetch-native-v2-worker-test native
docker build --target runtime -t clipfetch-native-v2-worker native
```

Expected: both exit 0; CTest reports all tests passed and runtime image health invokes gRPC Health.

## Task 7: Implement the parse use case and dispatcher

**Files:**
- Create application repository ports for resource, parse and events
- Create: `NativeV2ParseService.java`
- Create: `NativeV2ParseDispatcher.java`
- Test: `NativeV2ParseServiceTest.java`
- Test: `NativeV2ParseDispatcherTest.java`
- Test support: `server/native-v2-application/src/test/java/cc/ivera/magnet/v2/application/V2TestFixtures.java`

- [ ] **Step 1: Write RED service tests**

Cover:

```java
@Test void submitStoresOnlyNormalizedPublicIdentity() {
    var job=service.submitMagnet(7L,V2TestFixtures.publicV1Magnet());
    assertNull(job.getInputStorageKey());
    assertArrayEquals(V2TestFixtures.BTIH,job.getBtih());
    assertFalse(jobs.require(job.getParseUuid()).getInputSummary().contains("magnet:?"));
}

@Test void workerBytesAreReparsedBeforeReady() {
    var job=fixtures.pendingMagnet(7L,V2TestFixtures.BTIH);
    engine.resolveResult=V2TestFixtures.v1TorrentWithDifferentHash();
    dispatcher.dispatchOne(job.getParseUuid());
    assertEquals(ParseStatus.FAILED,jobs.require(job.getParseUuid()).getStatus());
    assertEquals(ErrorCodes.HASH_MISMATCH,jobs.require(job.getParseUuid()).getErrorCode());
}

@Test void aliasConflictRebindsJobToExistingWinner() {
    long winner=fixtures.readyResource(V2TestFixtures.BTIH);
    var job=fixtures.pendingMagnet(7L,V2TestFixtures.BTIH);
    engine.resolveResult=V2TestFixtures.publicV1Torrent();
    dispatcher.dispatchOne(job.getParseUuid());
    assertEquals(winner,jobs.require(job.getParseUuid()).getResourceId());
    assertEquals(1,resources.countByHash(V2TestFixtures.BTIH));
}

@Test void privateTorrentFailsPermanently() {
    var job=fixtures.pendingMagnet(7L,V2TestFixtures.BTIH);
    engine.resolveResult=V2TestFixtures.privateV1Torrent();
    dispatcher.dispatchOne(job.getParseUuid());
    assertEquals(ParseStatus.FAILED,jobs.require(job.getParseUuid()).getStatus());
    assertEquals(ErrorCodes.PRIVATE_TORRENT,jobs.require(job.getParseUuid()).getErrorCode());
}
```

Use an in-memory fake repository and fake engine; no Redis, DB or network.
Define `V2TestFixtures`, `FakeV2Repositories` and `FakeNativeV2Engine` in the application test source so every helper referenced by these tests has deterministic in-memory behavior.

- [ ] **Step 2: Implement submit**

`submitMagnet(userId, uri)` and `submitTorrent(userId, name, bytes)`:

- require overall v2 readiness and quota;
- normalize BTIH/BTMH and trackers;
- atomically persist upload input before DB job creation;
- return `parse_uuid` immediately;
- never cache a parse that writes database/files.

- [ ] **Step 3: Implement dispatch**

CAS `PENDING→RESOLVING`, reuse READY public metadata, otherwise call Worker with `parseUuid:revision:RESOLVE`. Reparse returned bytes in Java, reject private/unsafe/mismatch, resolve alias winner transactionally, atomically write final metadata, write files, then mark READY.

- [ ] **Step 4: Verify parse tests**

```powershell
mvn -q -pl server/native-v2-application,server/native-v2-scheduler -am -Dtest=NativeV2ParseServiceTest,NativeV2ParseDispatcherTest test
```

Expected: exit 0.

## Task 8: Implement task creation, scheduler and recovery

**Files:**
- Create: `NativeV2TaskService.java`
- Create: `NativeV2TaskDispatcher.java`
- Create: `NativeV2RetryPolicy.java`
- Create: `NativeV2CompletionPolicy.java`
- Test corresponding classes

- [ ] **Step 1: Write RED task/recovery tests**

```java
@Test void createRecomputesBytesAndClaimsOneResourceSlot() {
    var parse=fixtures.readyParse(7L,List.of(file(0,10),file(1,20)));
    var task=service.create(7L,parse.getParseUuid(),List.of(1));
    assertEquals(20,task.getSelectedBytes());
    assertEquals(task.getId(),sessions.require(task.getResourceId()).getActiveTaskId());
}

@Test void secondUserCannotClaimBusyResource() {
    long resource=fixtures.readyResourceWithFiles();
    service.create(7L,fixtures.readyParse(7L,resource).getParseUuid(),List.of(0));
    assertThrows(V2ConflictException.class,() ->
        service.create(8L,fixtures.readyParse(8L,resource).getParseUuid(),List.of(0)));
}

@Test void staleSnapshotCannotOverwritePauseRevision() {
    var task=fixtures.downloadingTask(7L,3L);
    service.pause(7L,task.getTaskUuid());
    dispatcher.applySnapshot(task.getId(),3L,snapshot(50));
    assertEquals(TaskStatus.PAUSING,tasks.require(task.getId()).getStatus());
}

@Test void firstCompleteSnapshotVerifiesAndSecondCompletes() {
    var task=fixtures.downloadingTask(7L,4L);
    dispatcher.applySnapshot(task.getId(),4L,completeSnapshot());
    assertEquals(TaskStatus.VERIFYING,tasks.require(task.getId()).getStatus());
    dispatcher.applySnapshot(task.getId(),5L,completeSnapshot());
    assertEquals(TaskStatus.COMPLETED,tasks.require(task.getId()).getStatus());
}

@Test void workerOfflinePauseIntentRunsBeforeAnyResume() {
    var task=fixtures.downloadingTask(7L,2L);
    engine.available=false;
    service.pause(7L,task.getTaskUuid());
    engine.available=true;
    dispatcher.tick();
    assertEquals(List.of("saveResume","pause","snapshot"),engine.calls());
}

@Test void corruptResumeFallsBackToRecheckWithoutOldCompletion() {
    var task=fixtures.retryTaskWithCorruptResume(7L);
    dispatcher.tick();
    assertTrue(engine.lastAddCommand().resumeData().length==0);
    assertEquals(TaskStatus.DOWNLOADING,tasks.require(task.getId()).getStatus());
    assertNotEquals(TaskStatus.COMPLETED,tasks.require(task.getId()).getStatus());
}
```

Define the local `file`, `snapshot`, `completeSnapshot` and `fixtures` helpers in `NativeV2TaskServiceTest`; they construct only the explicit states and byte counts used by each assertion.

- [ ] **Step 2: Implement transactional creation**

Require current user's READY parse, validate unique file indexes and configured limits, lock the Session row, claim `active_task_id`, write task/files/event as `QUEUED`, then commit before any Engine call.

- [ ] **Step 3: Implement task controls**

`pause(userId,taskUuid)` CASes to `PAUSING` even while Worker is offline. `resume` only accepts `PAUSED` and requires readiness before CAS to `QUEUED`. Invalid transitions throw `BusinessException(HttpStatus.CONFLICT,"当前任务状态不允许此操作")` at the backend boundary.

- [ ] **Step 4: Implement scheduler ordering**

For each due task:

1. If `PAUSING`, save resume atomically, pause, snapshot, then CAS PAUSED.
2. If QUEUED/RETRY_WAIT, add or reattach torrent, update full file-priority vector, resume, snapshot.
3. If active, refresh snapshot and per-file verified bytes.
4. Apply two-tick completion policy.
5. On transient failure write configured `RETRY_WAIT`; on permanent/operator failure write FAILED.
6. Ignore stale revision results and append `STALE_RESULT_IGNORED`.

Use MySQL time for due scans and lease calculation. Resume loss sets Session RECOVERING and forbids completion until a fresh non-recovering snapshot.

- [ ] **Step 5: Verify**

```powershell
mvn -q -pl server/native-v2-application,server/native-v2-scheduler -am test
```

Expected: exit 0 and fake Engine call order proves pause precedes resume after reconnect.

## Task 9: Assemble ClipFetch API, health and ticket streaming

**Files:**
- Create: `backend/src/main/java/cc/ivera/magnet/v2/interfaces/NativeV2Controller.java`
- Create: `NativeV2StreamController.java`
- Create: `NativeV2Dtos.java`
- Create: `server/native-v2-application/src/main/java/cc/ivera/magnet/v2/application/NativeV2TicketStore.java`
- Modify: `backend/src/main/java/cc/ivera/shared/config/WebConfig.java`
- Test: `backend/src/test/java/cc/ivera/magnet/v2/interfaces/NativeV2ControllerTest.java`
- Test: `NativeV2StreamControllerTest.java`

- [ ] **Step 1: Add RED MVC tests**

Assert:

```java
mockMvc.perform(get("/api/magnet/v2/engine/health").header("Authorization",bearer))
    .andExpect(status().isOk())
    .andExpect(jsonPath("$.data.configured").isBoolean());

mockMvc.perform(get("/api/magnet/v2/tasks/"+otherUsersTask).header("Authorization",bearer))
    .andExpect(status().isNotFound());

mockMvc.perform(get("/api/magnet/v2/downloads/stream/"+ticket)
        .header("Range","bytes=1-3"))
    .andExpect(status().isPartialContent())
    .andExpect(header().string("Content-Range","bytes 1-3/5"));
```

- [ ] **Step 2: Implement authenticated endpoints**

Use `AuthInterceptor.ATTR_USER` and existing `ApiResponse`/`BusinessException`. Add the approved parse/task/health routes, `@Valid` DTOs and page size max 30. Health always returns HTTP 200.

- [ ] **Step 3: Implement bounded local tickets**

Use 32 random bytes URL-safe encoded. Bind user/task/file/expiry, cap active tickets and enforce Redis issuance rate. Every stream request reloads owned task/file, requires COMPLETED, resolves safe real path, supports one HTTP Range and handles client abort without attempting JSON after commit.

- [ ] **Step 4: Add exactly one whitelist**

Add:

```java
"/api/magnet/v2/downloads/stream/*"
```

Do not whitelist health, parse, task, ticket-signing or any other v2 endpoint.

- [ ] **Step 5: Verify API**

```powershell
mvn -q -pl backend -am -Dtest=NativeV2ControllerTest,NativeV2StreamControllerTest test
```

Expected: exit 0; cross-user requests are 404 and stream works without Authorization only through a valid ticket.

## Task 10: Add the third frontend engine with polling safety

**Files:**
- Create: `frontend/src/api/magnetV2.js`
- Create: `frontend/src/components/NativeV2TorrentManager.vue`
- Modify: `frontend/src/components/MagnetEngineWorkspace.vue`
- Modify: `frontend/package.json`
- Create: `frontend/src/components/NativeV2TorrentManager.test.js`
- Create: `frontend/e2e/native-v2.spec.js`

- [ ] **Step 1: Install and configure test tools**

Add Vitest, Vue Test Utils, jsdom and Playwright dev dependencies plus scripts:

```json
"test:run": "vitest run",
"test:e2e": "playwright test"
```

- [ ] **Step 2: Write RED component tests**

With fake timers and mocked API, prove:

```js
it('does not poll tasks until health is connected', async () => {
  api.health.mockResolvedValue({ configured: true, connected: false })
  const wrapper = mount(NativeV2TorrentManager)
  await flushPromises(); vi.advanceTimersByTime(3000)
  expect(api.listTasks).not.toHaveBeenCalled()
  wrapper.unmount()
})

it('stops parse polling after the first request error', async () => {
  api.health.mockResolvedValue({ configured: true, connected: true })
  api.parseDetail.mockRejectedValue(new Error('引擎断开'))
  const wrapper = mount(NativeV2TorrentManager)
  await wrapper.vm.pollParse('00000000-0000-0000-0000-000000000001')
  await vi.advanceTimersByTimeAsync(4000)
  expect(api.parseDetail).toHaveBeenCalledTimes(1)
  wrapper.unmount()
})

it('clears every timer on unmount', async () => {
  const clear=vi.spyOn(globalThis,'clearInterval')
  const wrapper=mount(NativeV2TorrentManager)
  await flushPromises(); wrapper.unmount()
  expect(clear).toHaveBeenCalled()
})

it('never starts a task with an empty selection', async () => {
  const wrapper=mount(NativeV2TorrentManager)
  wrapper.vm.selectedFileIndexes=[]
  await wrapper.vm.createTask()
  expect(api.createTask).not.toHaveBeenCalled()
  expect(wrapper.text()).toContain('请至少选择一个文件')
})
```

Run `npm run test:run -- NativeV2TorrentManager.test.js`; expect failure because the component is absent.

- [ ] **Step 3: Add the API module**

Use only `request.js` and `errMsg`. Implement health, Magnet/torrent parse, parse detail/history, task create/list/detail, pause/resume and file ticket. Validate returned ticket URLs start with `/api/magnet/v2/downloads/stream/` and contain only URL-safe token characters.

- [ ] **Step 4: Add the lazy v2 workspace**

Extend route engine parsing to `native-v2`, render a third card and lazy-load `NativeV2TorrentManager`. Keep qBittorrent and Native v1 components unchanged. The v2 component uses 2-second parse and 1-second task polling only while health is connected; any error clears its timer; unmount clears all timers.

- [ ] **Step 5: Verify frontend**

```powershell
cd frontend
npm run test:run
npm run build
```

Expected: both exit 0 with a distinct lazy NativeV2 chunk.

## Task 11: Run real MySQL/Redis integration tests

**Files:**
- Test: `backend/src/test/java/cc/ivera/magnet/v2/infrastructure/MagnetV2MapperIT.java`
- Test: `backend/src/test/java/cc/ivera/magnet/v2/infrastructure/RedisNativeV2QuotaIT.java`
- Test: `backend/src/test/java/cc/ivera/magnet/v2/NativeV2MySqlContextIT.java`
- Test: `backend/src/test/java/cc/ivera/magnet/v2/NativeV2SqliteStartupTest.java`

- [ ] **Step 1: Complete Testcontainers coverage**

Use `mysql:8.4` and `redis:7`. Verify V24 migration, aliases, user filters, active slot row lock, CAS, logical delete, TTL and Redis-offline degradation.

- [ ] **Step 2: Run the profile**

```powershell
mvn -q -Pnative-v2-it verify
```

Expected: exit 0. If Docker is unavailable, report the profile as unverified; do not skip and claim success.

- [ ] **Step 3: Run SQLite regression**

```powershell
mvn -q -pl backend -Dtest=NativeV2SqliteStartupTest test
```

Expected: exit 0, v2 health configured=false, existing qBittorrent/v1 beans load.

## Task 12: Build deterministic Docker acceptance

**Files:**
- Create: `infra/native-v2/compose.acceptance.yml`
- Create: `infra/native-v2/seeder/Dockerfile`
- Create: `infra/native-v2/seeder/generate-fixtures.sh`
- Create: `infra/native-v2/runner/Dockerfile`
- Create: `infra/native-v2/runner/run-acceptance.sh`
- Create fixture payload manifests under `infra/native-v2/fixtures/`

- [ ] **Step 1: Generate deterministic local fixtures**

Generate fixed byte-pattern multi-file payloads and V1, V2 and Hybrid torrent metadata. Record SHA-256 in a manifest. Seeder rate-limit is low enough to observe pause and restarts. Only the acceptance Worker sets `MAGNET_V2_BLOCK_PRIVATE_NETWORKS=false`.

- [ ] **Step 2: Compose the real stack**

Start MySQL 8.4, Redis 7, API 8082, Worker 50051, local tracker/seeder and runner on one isolated network with one shared data volume. Do not publish MySQL/Redis or Worker gRPC to `0.0.0.0`.

- [ ] **Step 3: Automate the approved scenarios**

`run-acceptance.sh` must fail fast and verify all 15 cases in design section 17: ClipFetch auth, V1/V2/Hybrid parse, real Magnet metadata, partial selection, pause/resume, API restart, Worker restart, corrupt/missing resume, corrupt block redownload, two-tick completion, SHA-256, Range ticket, cross-user denial, Worker-offline degradation and SQLite smoke.

- [ ] **Step 4: Run acceptance**

```powershell
docker compose -f infra/native-v2/compose.acceptance.yml up --build --abort-on-container-exit --exit-code-from acceptance
```

Expected: exit 0. Always collect service logs, then run `down -v`; logs must not contain tokens, tracker queries or secrets.

## Task 13: Final verification, runtime restart and factual handoff

**Files:**
- Replace: `HANDOFF.md`

- [ ] **Step 1: Run serial backend verification**

```powershell
mvn -q clean test
mvn -q -Pnative-v2-it verify
mvn -q clean compile -DskipTests
```

Expected: all exit 0. Record actual test counts rather than planned counts.

- [ ] **Step 2: Run frontend and Worker verification**

```powershell
cd frontend
npm run test:run
npm run build
npm run test:e2e
cd ..
docker build --target test -t clipfetch-native-v2-worker-test native
```

Expected: all exit 0.

- [ ] **Step 3: Check diff and encoding**

Run `git diff --check`. Enumerate changed/untracked text files and reject UTF-8 or UTF-16 BOM. Inspect `git diff --stat` and verify every changed line belongs to the approved v2 scope.

- [ ] **Step 4: Restart backend on 8082**

Resolve the existing listener PID, stop only that process, start from `backend` with `SERVER_PORT=8082`, and verify exactly one listener after Spring Boot readiness. Do not reuse acceptance database credentials in the normal process.

- [ ] **Step 5: Replace HANDOFF**

Record every changed file group, exact command and Exit Code, Maven/Vitest/CTest/acceptance counts, listener PID, and any unverified boundary. State explicitly whether real Worker restart, final hash and cross-user tests passed. Do not claim production readiness if any mandatory scenario was skipped or failed. Do not create a commit.

## Plan self-review

- **Spec coverage:** Tasks cover architecture, ClipFetch auth, engine coexistence, MySQL/Redis gating, eight tables, V24 dual migrations, storage, proto, Worker, parsing, scheduling, recovery, two-tick completion, ticket Range streaming, frontend polling safety and deterministic acceptance.
- **Placeholder scan:** No TBD/TODO/future implementation placeholders are used. Each task has exact files, test intent, commands and expected results.
- **Type consistency:** The plan consistently uses `parse_uuid`, `resource_uuid`, `task_uuid`, `owner_epoch=1`, stable `taskUuid:revision:operation` command IDs, `file_verified_bytes`, `active_task_id`, and the approved state names.
- **Scope:** Magnet auth, users, exports, GC, aria2, monitoring, HA takeover and performance tuning are explicitly excluded.
