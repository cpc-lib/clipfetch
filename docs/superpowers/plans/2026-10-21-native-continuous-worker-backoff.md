# Native Continuous Worker Backoff Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Avoid repeating a full continuous Native round after a socket stall and centralize bounded retry/stall backoff without changing task safety semantics.

**Architecture:** Keep the change inside `NativeWorkerJobManager.runContinuous`. Add two package-visible pure policies for timeout classification and budget-bounded backoff, then use them at the existing retry and stall waits. A non-global `SocketTimeoutException` advances to the next bounded continuous round using the current durable task state; ordinary `IOException` retains the existing three-attempt per-round retry. No new configuration, thread, API, persistence, or network path is introduced.

**Tech Stack:** Java 21, Spring Boot Native BitTorrent worker, JUnit 5, Maven, PowerShell.

本计划不包含 commit；项目 `AGENTS.md` 明确禁止代理主动创建 commit。

---

### Task 1: Lock down C policies and timeout rollover with failing tests

**Files:**
- Create: `backend/src/test/java/cc/ivera/magnet/engine/piece/NativeWorkerJobManagerTest.java`

- [x] **Step 1: Add the failing pure-policy tests**

Add package-local tests that call the wished-for methods before production code exists:

```java
@Test
void socketTimeoutSkipsSameRoundRetryButOrdinaryIoRetries() {
    assertFalse(NativeWorkerJobManager.shouldRetryContinuousFailure(
            new SocketTimeoutException("peer stalled")));
    assertTrue(NativeWorkerJobManager.shouldRetryContinuousFailure(
            new IOException("connection reset")));
}

@Test
void continuousBackoffUsesRetryAndStallWindowsWithinBudget() {
    assertEquals(0, NativeWorkerJobManager.boundedContinuousBackoffMillis(0,0,1_000_000_000L));
    assertEquals(200, NativeWorkerJobManager.boundedContinuousBackoffMillis(1,0,1_000_000_000L));
    assertEquals(400, NativeWorkerJobManager.boundedContinuousBackoffMillis(2,0,1_000_000_000L));
    assertEquals(400, NativeWorkerJobManager.boundedContinuousBackoffMillis(0,1,1_000_000_000L));
    assertEquals(800, NativeWorkerJobManager.boundedContinuousBackoffMillis(0,2,1_000_000_000L));
    assertEquals(123, NativeWorkerJobManager.boundedContinuousBackoffMillis(1,2,123_500_000L));
    assertEquals(0, NativeWorkerJobManager.boundedContinuousBackoffMillis(1,1,0));
    assertEquals(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(Long.MAX_VALUE),
            NativeWorkerJobManager.boundedContinuousBackoffMillis(
                    Integer.MAX_VALUE,Integer.MAX_VALUE,Long.MAX_VALUE));
}
```

Use `java.io.IOException`, `java.net.SocketTimeoutException`, `org.junit.jupiter.api.Test`, and JUnit `assertEquals`, `assertFalse`, `assertTrue`.

- [x] **Step 2: Run the focused test to verify RED**

Run from `backend`:

```powershell
mvn -q "-Dtest=NativeWorkerJobManagerTest" test
```

Expected: Exit Code `1` with compilation errors for the two missing package-visible methods.

- [x] **Step 3: Add the real continuous timeout rollover regression**

Extend the same test with a fake `TaskAccess`, a `RoundInvoker` that throws `SocketTimeoutException` on its first invocation and returns a `COMPLETED` `TaskState` on its second invocation, and a real `NativeWorkerJobManager` submitted through `submitContinuous`. Configure `NativeTorrentProperties` with `enabled`, `workerRoundEnabled`, `asyncWorkerEnabled`, and `continuousWorkerEnabled` set to `true`, set `continuousMaxPieces=4`, `continuousMaxRounds=2`, `continuousMaxSeconds=10`, and `continuousStalledRounds=2`. Assert after `await(owner,job,Duration.ofSeconds(5))` that the snapshot is `SUCCEEDED` and the invoker count is `2`. The fake `prepare` must return the same READY revision; `pause` and `fenceOrphan` may return the current state. Close the manager in a `finally` block.

Expected before implementation: the focused test either cannot compile because the helper methods are absent or, after the pure methods exist but before wiring, the integration assertion observes three first-round invocations / non-success.

### Task 2: Implement the minimal policy and wire `runContinuous`

**Files:**
- Modify: `backend/src/main/java/cc/ivera/magnet/engine/piece/NativeWorkerJobManager.java`

- [x] **Step 1: Implement the pure timeout classifier**

Add:

```java
static boolean shouldRetryContinuousFailure(IOException failure) {
    return !(failure instanceof java.net.SocketTimeoutException);
}
```

Keep the caller’s existing `NativeNetworkBudget.deadlineExceeded(failure)` branch before this helper.

- [x] **Step 2: Implement overflow-safe bounded backoff**

Add the package-visible method with 200ms/400ms bases, saturating doubling, and remaining-nanosecond truncation. A counter of zero contributes no delay. Return `long`; use `Math.min` only after saturating the two independent delay values.

- [x] **Step 3: Replace duplicate retry/stall delay calculations**

In `runContinuous`:

1. After the existing global deadline, interrupt, READY, and revision checks in the `IOException` catch, if `shouldRetryContinuousFailure(io)` is false, assign `new RoundReport(0,true,null,current)` and `break` the inner retry loop.
2. For ordinary retryable IO, keep `++failures>=3` and all existing state guards; replace `200L << (failures-1)` with `boundedContinuousBackoffMillis(failures,stalled,remainingNanos)`.
3. Replace the stall `400L*stalled` calculation with `boundedContinuousBackoffMillis(0,stalled,remainingNanos)`.
4. Preserve existing interrupt handling and `Thread.sleep` behavior; do not change `stopPartial`, task revision, or round limits.

- [x] **Step 4: Run the focused GREEN test**

Run:

```powershell
mvn -q "-Dtest=NativeWorkerJobManagerTest" test
```

Expected: Exit Code `0`; pure policy and timeout rollover tests pass.

### Task 3: Verify Native behavior and hand off

**Files:**
- Modify: `docs/superpowers/plans/2026-10-21-native-continuous-worker-backoff.md`
- Replace: `HANDOFF.md`

- [x] **Step 1: Run related Native regression tests**

```powershell
mvn -q "-Dtest=NativeWorkerJobManagerTest,PeerBlockMailboxTest,PeerHealthTrackerTest,PeerRoundConnectionPoolTest,PeerIncrementalFrameReaderTest" test
```

Expected: Exit Code `0`.

- [x] **Step 2: Run full backend verification**

Run separately from `backend`:

```powershell
mvn -q clean test
mvn -q clean compile -DskipTests
mvn -q test
```

Record exact Surefire suite/test/failure/error/skip totals; all commands must exit `0`.

- [x] **Step 3: Run diff and encoding checks**

Run `git diff --check` from the repository root and scan every changed text file for UTF-8/UTF-16 BOM. Expected: diff Exit Code `0` except the already recorded `HANDOFF.md` line-ending warning and zero BOM files.

- [x] **Step 4: Perform focused read-only review**

Review only timeout classification, same-round rollover, backoff overflow/budget boundaries, cancellation/revision guards, and the manager regression test. Resolve Critical or Important findings before handoff; do not create a commit.

- [x] **Step 5: Restart and verify the backend**

Restart with the existing hidden launcher and `SERVER_PORT=8082`. Verify Spring Boot/Tomcat readiness and exactly one listener with `Get-NetTCPConnection -LocalPort 8082 -State Listen`. Do not resume or create a Native download task.

- [x] **Step 6: Replace the handoff with actual evidence**

Fully replace `HANDOFF.md` with the C changes, RED/GREEN/focused/full verification exit codes, BOM result, review result, live listener PID, and the unchanged benchmark boundary. Do not claim speed improvement without a comparable unfinished task.

## Scope boundaries

- Do not change Peer timeout values, Peer selection, Actor admission, Piece request windows, database schema, task API, or front-end code.
- Do not add a new retry/backoff environment variable; preserve the existing 200ms/400ms behavior for ordinary failure/stall counts and only centralize its safe bounded calculation.
- Do not restart, resume, or create the user’s completed Ubuntu magnet task.
