# Native Peer 自适应读等待优化实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the fixed 50ms Native mailbox socket-read polling window by waiting only until the earlier of the task deadline, current Peer no-progress deadline, and the existing Mailbox 250ms overdue/reassignment check window.

**Architecture:** Add package-visible pure millisecond conversion policies to `PeerRoundConnectionPool`, then pass the bounded result to the existing incremental `PeerIncrementalFrameReader`. Reuse the existing 250ms Mailbox overdue/reassignment cadence so cross-Actor CANCEL and overdue lease handling are not delayed by a long read; reject a Frame that returns after the task deadline. The reader, Socket close-on-cancel path, adaptive Peer stall deadline, request window, mailbox, and protocol parser remain otherwise unchanged. No new configuration, thread, selector, or network path is introduced.

**Tech Stack:** Java 21, Spring Boot Native BitTorrent engine, JUnit 5, Maven, PowerShell.

本计划不包含 commit 步骤；项目 `AGENTS.md` 明确禁止代理主动创建 commit。

---

### Task 1: Lock down the read-timeout conversion and Reader behavior with failing tests

**Files:**
- Modify: `backend/src/test/java/cc/ivera/magnet/engine/piece/PeerRoundConnectionPoolTest.java`
- Create: `backend/src/test/java/cc/ivera/magnet/engine/piece/PeerIncrementalFrameReaderTest.java`

- [x] **Step 1: Add the failing pure-policy tests**

Append these tests to `PeerRoundConnectionPoolTest`. They call the production method before it exists, so the focused compilation must fail:

```java
    @Test
    void readWaitUsesTheEarlierPeerProgressDeadline() {
        assertEquals(3_000,
                PeerRoundConnectionPool.boundedReadTimeoutMillis(
                        Duration.ofSeconds(5).toNanos(),
                        Duration.ofSeconds(3).toNanos()));
    }

    @Test
    void readWaitUsesTheEarlierTaskDeadline() {
        assertEquals(2_000,
                PeerRoundConnectionPool.boundedReadTimeoutMillis(
                        Duration.ofSeconds(2).toNanos(),
                        Duration.ofSeconds(3).toNanos()));
    }

    @Test
    void readWaitKeepsExpiredOrSubMillisecondWindowProgressing() {
        assertEquals(1,
                PeerRoundConnectionPool.boundedReadTimeoutMillis(
                        500_000L,Duration.ofSeconds(1).toNanos()));
        assertEquals(1,
                PeerRoundConnectionPool.boundedReadTimeoutMillis(
                        0,Duration.ofSeconds(1).toNanos()));
        assertEquals(1,
                PeerRoundConnectionPool.boundedReadTimeoutMillis(
                        -1,Duration.ofSeconds(1).toNanos()));
    }

    @Test
    void readWaitCapsAtIntegerMaxMillisWithoutOverflow() {
        assertEquals(Integer.MAX_VALUE,
                PeerRoundConnectionPool.boundedReadTimeoutMillis(
                        Long.MAX_VALUE,Long.MAX_VALUE));
    }
```

Add this third-window test to the same class:

```java
    @Test
    void readWaitUsesTheExistingMailboxCheckWindow() {
        assertEquals(250,
                PeerRoundConnectionPool.boundedReadTimeoutMillis(
                        Duration.ofSeconds(5).toNanos(),
                        Duration.ofSeconds(3).toNanos(),
                        Duration.ofMillis(250).toNanos()));
    }
```

The pure-policy tests cover the earlier Peer progress deadline, the earlier task deadline, the existing Mailbox check bound, the 1ms floor for positive sub-millisecond and expired windows, and overflow-safe integer capping.

Also add deterministic `PeerIncrementalFrameReaderTest` coverage using a scripted `InputStream` and recording `Socket` double: one test verifies each read applies a changed `SoTimeout`; one test verifies a partial header survives a timeout and the remaining header/payload produces a valid Frame on the next read; another verifies a partial payload survives a timeout. The tests must not depend on wall-clock sleeps or external Peers.

Add a pure-policy test for the overdue Mailbox branch: an expired check with available reassignment slots remains expired for immediate processing, while an expired check with zero available slots falls back to the existing 250ms cadence.

- [x] **Step 2: Run the focused test and verify RED**

Run from `backend`:

```powershell
mvn -q "-Dtest=PeerRoundConnectionPoolTest" test
```

Expected: Exit Code `1` during test compilation because `PeerRoundConnectionPool.boundedReadTimeoutMillis(long,long)` is not defined. Do not modify production code until this missing-method failure is observed.

### Task 2: Apply the deadline-driven timeout to the mailbox read loop

**Files:**
- Modify: `backend/src/main/java/cc/ivera/magnet/engine/piece/PeerRoundConnectionPool.java:129-150,722-735`

- [x] **Step 1: Add the minimal pure conversion policy**

Add this package-visible method immediately after `adaptiveNoProgressTimeoutNanos` and before `shouldExitIdleMailbox`:

```java
    static int boundedReadTimeoutMillis(long remainingDeadlineNanos,
                                        long remainingProgressNanos) {
        return boundedReadTimeoutMillis(remainingDeadlineNanos,
                remainingProgressNanos,Long.MAX_VALUE);
    }

    static int boundedReadTimeoutMillis(long remainingDeadlineNanos,
                                        long remainingProgressNanos,
                                        long remainingMailboxCheckNanos) {
        long remainingNanos=Math.min(remainingDeadlineNanos,
                Math.min(remainingProgressNanos,remainingMailboxCheckNanos));
        if(remainingNanos<=0)return 1;
        long millis=remainingNanos/1_000_000L;
        if(remainingNanos%1_000_000L!=0)millis++;
        return (int)Math.min(Integer.MAX_VALUE,Math.max(1,millis));
    }

    static long remainingMailboxCheckNanos(long nextReassignmentCheck,
                                           long now,
                                           long fallbackNanos) {
        return nextReassignmentCheck==0?fallbackNanos:
                nextReassignmentCheck-now;
    }

    static long boundedMailboxCheckNanos(long remainingCheckNanos,
                                         int availableSlots,
                                         long fallbackNanos) {
        return remainingCheckNanos<=0 && availableSlots<=0?
                fallbackNanos:remainingCheckNanos;
    }
```

Keep the two-argument method as the general conversion policy and add a three-argument overload that takes the existing Mailbox check window; the two-argument method delegates with an unbounded third window. The methods only convert caller-provided remaining windows. They do not read Socket, Peer, mailbox, or task state, and they avoid `nanos + 999_999` overflow by rounding after division.

- [x] **Step 2: Replace only the fixed read timeout at the mailbox call site**

Immediately before the existing `readFrame` call in `runMailbox`, add the current read-window calculation and pass its result instead of `50`:

```java
                long readNow=System.nanoTime();
                long readProgress=incremental.hasPartialFrame()?
                        incremental.lastProgressNanos():idleSince;
                long mailboxCheckRemainingNanos=remainingMailboxCheckNanos(
                        nextReassignmentCheck,readNow,
                        MAILBOX_REASSIGNMENT_INTERVAL_NANOS);
                mailboxCheckRemainingNanos=boundedMailboxCheckNanos(
                        mailboxCheckRemainingNanos,slots,
                        MAILBOX_REASSIGNMENT_INTERVAL_NANOS);
                int readTimeoutMillis=boundedReadTimeoutMillis(
                        deadline-readNow,
                        noProgressTimeoutNanos-(readNow-readProgress),
                        mailboxCheckRemainingNanos);
                PeerWireCodec.Message frame=incremental.readFrame(
                        link.in,link.socket,readTimeoutMillis);
                if(frame!=null && System.nanoTime()>=deadline)
                    throw new SocketTimeoutException("Multi-Piece Peer deadline exceeded");
```

Leave the following `if(frame==null)` block unchanged, including its fresh `now`, `progress`, stall comparison, `SocketTimeoutException`, and `continue`. Define `MAILBOX_REASSIGNMENT_INTERVAL_NANOS` from the existing 250ms reassignment cadence and use the same value for `nextReassignmentCheck`; this preserves overdue lease and protocol CANCEL checks while reducing read wakeups from 50ms to at most 250ms. Do not alter `PeerIncrementalFrameReader` production behavior, Socket close handling, partial-frame state, or any other read path.

- [x] **Step 3: Run the focused tests and verify GREEN**

Run from `backend`:

```powershell
mvn -q "-Dtest=PeerRoundConnectionPoolTest" test
```

Expected: Exit Code `0`; the four new conversion tests and all existing idle-exit/adaptive-stall tests pass.

### Task 3: Verify unchanged Native behavior and hand off

**Files:**
- Modify: `docs/superpowers/plans/2026-10-21-native-adaptive-read-wait.md`
- Replace: `HANDOFF.md`

- [x] **Step 1: Run the related Native regression tests**

Run from `backend`:

```powershell
mvn -q "-Dtest=PeerBlockMailboxTest,PeerHealthTrackerTest,PeerRoundConnectionPoolTest,PeerIncrementalFrameReaderTest" test
```

Expected: Exit Code `0`; mailbox wakeup, Peer cooldown/lease, connection-pool timeout, and incremental Reader tests pass.

- [x] **Step 2: Run full backend verification**

With the backend stopped if it holds Maven target files, run these commands separately from `backend`:

```powershell
mvn -q clean test
mvn -q clean compile -DskipTests
mvn -q test
```

Record the actual Surefire suite/test/failure/error/skip counts. All three commands must exit `0`.

- [x] **Step 3: Run diff and encoding checks**

From the repository root run:

```powershell
git diff --check
```

Then scan every changed `.java`, `.js`, `.vue`, `.xml`, `.yml`, `.yaml`, `.sql`, `.md`, `.ps1`, `.conf`, and `.properties` file for UTF-8/UTF-16 BOM. Expected: diff exit `0` except any already-recorded line-ending warning, and zero BOM files.

- [x] **Step 4: Request focused read-only review**

Review only the A conversion policy, `runMailbox` call-site wiring, deadline/overflow behavior, cancellation boundary, and regression tests. Resolve Critical or Important findings before handoff; do not create a commit.

- [x] **Step 5: Restart and verify the backend**

Start from `backend` with `SERVER_PORT=8082` using the existing hidden Maven launcher convention. Verify the startup log reaches Spring Boot/Tomcat readiness and:

```powershell
Get-NetTCPConnection -LocalPort 8082 -State Listen
```

reports exactly one listener. Do not resume or create a Native download task.

- [x] **Step 6: Replace the handoff with actual evidence**

Fully replace `HANDOFF.md` with this round’s actual A files, RED/GREEN/focused/full verification exit codes, BOM result, review result, runtime listener PID, and the boundary that no speed percentage is claimed without a comparable unfinished Native task. Leave no unverified claim or stale waiting-for-user status.

## Scope boundaries

- Do not change Peer first-block/stall/connect/handshake timeout values or RTT calculations.
- Do not change Block request windows, Piece/Actor counts, mailbox overdue/reassignment timing, Peer selection, failure accounting, or connection reuse.
- Do not add configuration keys, timers, scheduling annotations, threads, queues, caches, database migrations, APIs, or frontend code. Reuse the existing 250ms mailbox check cadence rather than introducing another timeout parameter.
- Preserve all earlier Native optimizations and the completed B cooldown-aware wait implementation in the dirty worktree.

## Plan self-review

- Spec coverage: the plan covers the fixed 50ms root cause, pure min/deadline/Mailbox conversion, 1ms floor, integer overflow cap, hard post-read deadline check, mailbox wiring, cancellation/partial-frame invariants, deterministic Reader regression tests, full verification, review, restart, and handoff.
- Placeholder scan: all implementation steps include exact paths, method signatures, code, commands, and expected outcomes; no unresolved placeholder or unspecified helper remains.
- Type consistency: tests call the two- and three-argument `PeerRoundConnectionPool.boundedReadTimeoutMillis` overloads, Task 2 defines both signatures, and `runMailbox` passes its `int` result to the existing `readFrame(InputStream,Socket,int)` method.

## Completion evidence

- Related Native regression tests exited `0`.
- `mvn -q clean test`, `mvn -q clean compile -DskipTests`, and `mvn -q test` each exited `0`; the final Surefire result is `4 suites / 39 tests / 0 failures / 0 errors / 0 skipped`.
- `git diff --check` exited `0`; the only output was the existing `HANDOFF.md` LF-to-CRLF warning. The changed-text BOM scan found `0` BOM files.
- Two focused read-only review rounds returned findings that were fixed. A final follow-up reviewer could not run because the account usage limit was reached; the final delta was manually reviewed and covered by the regression tests.
- The backend was restarted with the latest code. Spring Boot/Tomcat reached readiness on port `8082`, with exactly one listener owned by PID `57856`; no Native download task was resumed or created.
