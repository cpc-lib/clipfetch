# Native Peer Cooldown-Aware Wait Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reduce Native Actor wakeups during Peer cooldowns by waiting until the earliest relevant cooldown or the existing quiet-stall boundary, while preserving lease selection and notification behavior.

**Architecture:** Keep the existing monitor-based `PeerHealthTracker.awaitLease` loop and `nextLeaseDelayNanos` state inspection. Add one package-visible pure wait-window policy method for deterministic tests, use its result to replace the prior 50–250ms polling sleep, and reuse a single existing 3-second quiet-stall constant in `PeerMultiplexedRound` for both the wait bound and idle exit check. No new thread, timer, configuration, or network path is introduced.

**Tech Stack:** Java 21, Spring Boot Native BitTorrent engine, JUnit 5, Maven, PowerShell.

---

### Task 1: Lock down the wait-window policy with failing tests

**Files:**
- Modify: `backend/src/test/java/cc/ivera/magnet/engine/piece/PeerHealthTrackerTest.java`

- [x] **Step 1: Add the failing policy tests**

Add these tests to `PeerHealthTrackerTest`. The new production method does not exist yet, so the focused test must fail at compilation:

```java
    @Test
    void leaseWaitUsesTheEarlierCooldownOrDeadline() {
        assertEquals(TimeUnit.MILLISECONDS.toNanos(500),
                PeerHealthTracker.boundedLeaseWaitNanos(
                        TimeUnit.SECONDS.toNanos(3), TimeUnit.MILLISECONDS.toNanos(500)));
        assertEquals(TimeUnit.MILLISECONDS.toNanos(300),
                PeerHealthTracker.boundedLeaseWaitNanos(
                        TimeUnit.MILLISECONDS.toNanos(300), TimeUnit.MILLISECONDS.toNanos(500)));
        assertEquals(TimeUnit.SECONDS.toNanos(3),
                PeerHealthTracker.boundedLeaseWaitNanos(
                        TimeUnit.SECONDS.toNanos(3), Long.MAX_VALUE));
    }

    @Test
    void leaseWaitUsesOneMillisecondProgressFloorForExpiredCooldown() {
        assertEquals(TimeUnit.MILLISECONDS.toNanos(1),
                PeerHealthTracker.boundedLeaseWaitNanos(
                        TimeUnit.SECONDS.toNanos(3), 0));
        assertEquals(0,
                PeerHealthTracker.boundedLeaseWaitNanos(0, TimeUnit.MILLISECONDS.toNanos(500)));
    }
```

The assertions cover an earlier cooldown, an earlier caller deadline, no finite cooldown, the zero-delay progress floor, and an already expired caller deadline. Use the existing `TimeUnit` import in the test file.

- [x] **Step 2: Run the focused test and verify RED**

Run from `backend`:

```powershell
mvn -q "-Dtest=PeerHealthTrackerTest" test
```

Expected: compilation fails because `PeerHealthTracker.boundedLeaseWaitNanos(long,long)` is not defined yet. Do not change production code until this missing-method failure is observed.

### Task 2: Apply the cooldown-aware wait in the tracker

**Files:**
- Modify: `backend/src/main/java/cc/ivera/magnet/engine/piece/PeerHealthTracker.java:314-375`

- [x] **Step 1: Add the pure wait-window policy**

Add this package-visible static method immediately before `awaitLease`:

```java
    static long boundedLeaseWaitNanos(long remainingNanos,long nextLeaseDelayNanos) {
        if(remainingNanos<1)return 0;
        if(nextLeaseDelayNanos<1)
            return Math.min(remainingNanos,TimeUnit.MILLISECONDS.toNanos(1));
        return Math.min(remainingNanos,nextLeaseDelayNanos);
    }
```

It must only calculate the wait window; it must not read or mutate tracker state.

- [x] **Step 2: Replace the polling wait with the calculated window**

In `awaitLease`, keep the existing validation, initial lease attempt, deadline calculation, `wait`, interruption behavior, and re-selection loop. Replace the prior polling wait calculation with:

```java
            long nextDelay=nextLeaseDelayNanos(pieces,availability);
            long waitNanos=boundedLeaseWaitNanos(remaining,nextDelay);
            if(waitNanos<1)return null;
            long waitMillis=TimeUnit.NANOSECONDS.toMillis(waitNanos);
            int waitNanosRemainder=(int)(waitNanos-
                    TimeUnit.MILLISECONDS.toNanos(waitMillis));
            wait(waitMillis,waitNanosRemainder);
```

The `Object.wait(long,int)` overload preserves the calculated nanosecond budget without rounding it upward; the window is already no greater than the caller deadline. The existing `nextLeaseDelayNanos` call remains under the same synchronized monitor as the lease attempt.

- [x] **Step 3: Run the focused tests and verify GREEN**

Run:

```powershell
mvn -q "-Dtest=PeerHealthTrackerTest" test
```

Expected: Exit Code `0`; the new policy tests, existing failed-Peer cooldown/fallback tests, compatibility overload test, and notification wakeup test pass.

### Task 3: Extend the wait bound without changing quiet-stall behavior

**Files:**
- Modify: `backend/src/main/java/cc/ivera/magnet/engine/piece/PeerMultiplexedRound.java:15-20,180-200,268-272`
- Modify: `backend/src/test/java/cc/ivera/magnet/engine/piece/PeerHealthTrackerTest.java`

- [x] **Step 1: Add tracker delay regression coverage**

Add these tests after the policy tests. They document the existing `nextLeaseDelayNanos` contract used by the new wait path:

```java
    @Test
    void nextLeaseDelayUsesTheEarliestIdleServingPeer() {
        var first = new InetSocketAddress("192.0.2.10", 6881);
        var second = new InetSocketAddress("192.0.2.11", 6881);
        var now = new AtomicLong();
        var health = new PeerHealthTracker(List.of(first, second), now::get, 8_000);
        health.failure(first);
        health.failure(second);
        health.failure(second);
        var availability = new PeerPieceAvailability(1, Map.of());

        assertEquals(TimeUnit.MILLISECONDS.toNanos(250),
                health.nextLeaseDelayNanos(Set.of(0), availability));
    }

    @Test
    void nextLeaseDelayReportsNoFiniteDelayWhenServingPeerIsBusy() {
        var peer = new InetSocketAddress("192.0.2.10", 6881);
        var health = new PeerHealthTracker(List.of(peer), () -> 0L, 8_000);
        var availability = new PeerPieceAvailability(1, Map.of());

        assertEquals(peer, health.leaseExclusive(Set.of(0), availability));
        assertEquals(Long.MAX_VALUE,
                health.nextLeaseDelayNanos(Set.of(0), availability));
        health.release(peer);
    }

    @Test
    void nextLeaseDelayReportsNoFiniteDelayWhenPeerCannotServePiece() {
        var peer = new InetSocketAddress("192.0.2.10", 6881);
        var health = new PeerHealthTracker(List.of(peer), () -> 0L, 8_000);
        var availability = new PeerPieceAvailability(1,
                Map.of(peer, new java.util.BitSet()));

        assertEquals(Long.MAX_VALUE,
                health.nextLeaseDelayNanos(Set.of(0), availability));
    }
```

The first test uses two different failure counts to ensure the earliest 250ms cooldown wins; the second verifies that a busy Peer relies on monitor notification rather than a fabricated cooldown deadline; the third verifies that a known-false availability report is not treated as a cooldown candidate.

- [x] **Step 2: Add one shared quiet-stall bound**

Add this field after the existing logger in `PeerMultiplexedRound`:

```java
    private static final long QUIET_STALL_NANOS=TimeUnit.SECONDS.toNanos(3);
```

- [x] **Step 3: Use the bound for Actor waits and the existing idle exit**

Replace the prior Actor polling block:

```java
                                long retryDelay=health.nextLeaseDelayNanos(
                                        remaining,availability);
                                long waitMillis=50;
                                if(retryDelay>0 && retryDelay<Long.MAX_VALUE)
                                    waitMillis=Math.max(10,Math.min(250,
                                            TimeUnit.NANOSECONDS.toMillis(retryDelay+999_999L)));
                                try {Thread.sleep(waitMillis);}
```

with the existing monitor wait call:

```java
                                        failedThisRound,QUIET_STALL_NANOS);
```

Replace only the Actor quiet-idle comparison:

```java
                                        (System.nanoTime()-lastActivityNanos.get())>=QUIET_STALL_NANOS) {
```

Leave the owner deadline extension, drain deadline, Mailbox 250ms checks, socket handling, failure accounting, and cancellation path unchanged.

- [x] **Step 4: Run the related Native tests**

Run from `backend`:

```powershell
mvn -q "-Dtest=PeerBlockMailboxTest,PeerHealthTrackerTest,PeerRoundConnectionPoolTest" test
```

Expected: Exit Code `0`; all new policy/delay tests and existing Native scheduling tests pass.

### Task 4: Verify, review, and hand off the runtime change

**Files:**
- Modify: `docs/superpowers/plans/2026-10-21-native-peer-cooldown-wait.md`
- Replace: `HANDOFF.md`

- [x] **Step 1: Run the full backend verification**

Stop the current backend only if it holds Maven target files, then run separately from `backend`:

```powershell
mvn -q clean test
mvn -q clean compile -DskipTests
mvn -q test
```

Expected: all three commands exit `0`; record the actual Surefire test count and zero failures/errors.

- [x] **Step 2: Run diff and encoding checks**

From the repository root run `git diff --check`, then scan every changed `.java`, `.js`, `.vue`, `.xml`, `.yml`, `.yaml`, `.sql`, `.md`, `.ps1`, `.conf`, and `.properties` file for UTF-8/UTF-16 BOM. Expected: diff exit `0` with only any already-recorded line-ending warning, and no BOM files.

- [x] **Step 3: Request a focused code review**

Ask a read-only reviewer to inspect only the B wait-window policy, `awaitLease` wiring, the shared 3-second bound, and the new tests. Resolve Critical or Important findings before handoff; do not create a commit.

- [x] **Step 4: Restart and verify the backend**

Start from `backend` with:

```powershell
$env:SERVER_PORT='8082'; mvn spring-boot:run
```

Verify `Get-NetTCPConnection -LocalPort 8082 -State Listen` reports exactly one listener and the startup log reaches Spring Boot/Tomcat readiness. Do not resume or create a Native download task.

- [x] **Step 5: Replace the handoff with actual evidence**

Fully replace `HANDOFF.md` with this round’s actual files, RED/GREEN/full verification exit codes, listener PID, code-review result, and the boundary that no speed percentage is claimed without a comparable unfinished Native task. Do not create a commit.

## Scope boundaries

- Do not change Peer cooldown durations, failure counters, lease ranking, failed-Peer fallback, Peer count, Actor exploration slots, Block request windows, socket timeouts, or network protocol.
- Do not change the other 250ms Mailbox overdue/reassignment checks.
- Do not add configuration keys, timers, scheduling annotations, threads, queues, caches, database migrations, APIs, or frontend code.
- Preserve all earlier Native optimization changes in the dirty worktree.

## Plan self-review

- Spec coverage: dynamic cooldown selection, 1ms progress floor, 3-second Actor bound, monitor notifications, interruption, unchanged lease semantics, regression tests, full verification, review, restart, and handoff are all represented.
- Placeholder scan: every code step has concrete paths, signatures, code, commands, and expected results; no unresolved marker or unspecified helper remains.
- Type consistency: the tests call `PeerHealthTracker.boundedLeaseWaitNanos(long,long)` and `nextLeaseDelayNanos(Set<Integer>,PeerPieceAvailability)`; the implementation defines the first and keeps the second unchanged; the Actor passes `QUIET_STALL_NANOS` to the existing four-argument `awaitLease`.
