# Native Mailbox Work Wakeup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the Native multiplexed mailbox's fixed idle sleep with a monitor wakeup that reacts immediately to relevant mailbox state changes while retaining a 15 ms timeout fallback.

**Architecture:** `PeerBlockMailbox` remains the owner of synchronized mailbox state and exposes one package-local bounded wait. State transitions notify the mailbox monitor. `PeerRoundConnectionPool.runMailbox` replaces only its no-outstanding-Block sleep with that wait; all protocol, request-window, cancellation, and idle-exit behavior remains unchanged.

**Tech Stack:** Java 21, Spring Boot, JUnit 5, Maven, existing Native BitTorrent mailbox and reusable Peer connection pool.

---

### Task 1: Add the mailbox wakeup regression test

**Files:**
- Create: `backend/src/test/java/cc/ivera/magnet/engine/piece/PeerBlockMailboxTest.java`

- [x] **Step 1: Write the failing concurrency test**

Create a package-local test that consumes all initial Blocks with one Peer, waits on a second Peer, disconnects the first Peer, and verifies the waiter completes before a 250 ms bound. The test must also confirm that the released Blocks can then be claimed by the waiting Peer.

```java
package cc.ivera.magnet.engine.piece;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PeerBlockMailboxTest {
    @Test
    void waitingPeerWakesWhenDisconnectedPeerReleasesBlocks() throws Exception {
        var firstPeer=new InetSocketAddress("192.0.2.10",6881);
        var waitingPeer=new InetSocketAddress("192.0.2.11",6881);
        var availability=new PeerPieceAvailability(1,Map.of());
        var mailbox=new PeerBlockMailbox(List.of(
                new PeerBlockMailbox.Key(0,0,PieceBlockPlanner.BLOCK_BYTES),
                new PeerBlockMailbox.Key(0,PieceBlockPlanner.BLOCK_BYTES,
                        PieceBlockPlanner.BLOCK_BYTES)),16);

        assertEquals(2,mailbox.claim(firstPeer,availability,0,16).size());

        var started=new CountDownLatch(1);
        var completed=new CountDownLatch(1);
        var failure=new AtomicReference<Throwable>();
        var waiter=new Thread(()->{
            started.countDown();
            try {
                mailbox.awaitStateChange(waitingPeer,Duration.ofMillis(250).toNanos());
            }catch(Throwable error) {
                failure.set(error);
            }finally {
                completed.countDown();
            }
        },"peer-mailbox-waiter");

        waiter.start();
        assertTrue(started.await(1,TimeUnit.SECONDS));
        TimeUnit.MILLISECONDS.sleep(40);
        mailbox.disconnect(firstPeer);

        try {
            assertTrue(completed.await(200,TimeUnit.MILLISECONDS));
        }finally {
            waiter.interrupt();
            waiter.join(1_000);
        }

        assertNull(failure.get());
        assertEquals(2,mailbox.claim(waitingPeer,availability,System.nanoTime(),16).size());
    }
}
```

- [x] **Step 2: Run the focused test and confirm RED**

Run from `backend`:

```powershell
mvn -q "-Dtest=PeerBlockMailboxTest" test
```

Expected: Exit Code `1` because `PeerBlockMailbox.awaitStateChange(...)` does not exist yet. The failure must be a missing-method compilation failure, not a test import or timing failure.

### Task 2: Implement bounded mailbox waiting and notifications

**Files:**
- Modify: `backend/src/main/java/cc/ivera/magnet/engine/piece/PeerBlockMailbox.java`

- [x] **Step 1: Add the package-local bounded wait**

Add this synchronized method near the mailbox state accessors:

```java
synchronized void awaitStateChange(InetSocketAddress peer,long maxWaitNanos)
        throws InterruptedException {
    if(peer==null || maxWaitNanos<1)
        throw new IllegalArgumentException("Invalid mailbox wait");
    if(peerOutstanding(peer)>0 || closedForClaiming())return;
    long millis=java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(maxWaitNanos);
    int nanos=(int)(maxWaitNanos-
            java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(millis));
    wait(millis,nanos);
}
```

The wait must release the mailbox monitor, allow spurious wakeups, and preserve `InterruptedException` for the caller.

- [x] **Step 2: Notify only relevant mailbox state transitions**

Keep existing state changes and add `notifyAll()` at these points:

```java
synchronized void closeForClaiming() {
    closedForClaiming=true;
    notifyAll();
}
synchronized void reopenForClaiming() {
    closedForClaiming=false;
    notifyAll();
}
```

After an accepted delivery changes `completed`, `unfinished`, or `remainingByPiece`, notify before returning the `Receipt`. After `disconnect` finishes releasing the Peer leases, notify. After `reassignOverdue` creates at least one transfer, notify before returning the transfer list. Do not notify from every read-only accessor or change claim ranking.

- [x] **Step 3: Run the focused tests and confirm GREEN**

Run:

```powershell
mvn -q "-Dtest=PeerBlockMailboxTest,PeerHealthTrackerTest,PeerRoundConnectionPoolTest" test
```

Expected: Exit Code `0`; the new concurrency test completes before its 200 ms assertion and claims both released Blocks.

### Task 3: Replace the fixed idle sleep in the reusable Peer loop

**Files:**
- Modify: `backend/src/main/java/cc/ivera/magnet/engine/piece/PeerRoundConnectionPool.java:689-708`

- [x] **Step 1: Replace only the no-outstanding-Block sleep**

Keep the existing three-second idle exit, control-frame read, and `continue`. Replace only:

```java
try {Thread.sleep(15);}
catch(InterruptedException stopped) {
    Thread.currentThread().interrupt();
    throw new IOException("Multi-Piece actor interrupted",stopped);
}
```

with:

```java
try {
    mailbox.awaitStateChange(peer,TimeUnit.MILLISECONDS.toNanos(15));
}catch(InterruptedException stopped) {
    Thread.currentThread().interrupt();
    throw new IOException("Multi-Piece actor interrupted",stopped);
}
```

Do not alter `pipeline.nextBatch`, `mailbox.claim`, `reassignOverdue`, socket reads, request encoding, cancellation queues, or adaptive timeout calculations.

- [x] **Step 2: Run the focused connection-pool tests**

Run:

```powershell
mvn -q "-Dtest=PeerBlockMailboxTest,PeerRoundConnectionPoolTest" test
```

Expected: Exit Code `0`.

### Task 4: Verify, restart, and update handoff

**Files:**
- Modify: `docs/superpowers/plans/2026-10-21-native-mailbox-work-wakeup.md`
- Replace: `HANDOFF.md`

- [x] **Step 1: Run full verification**

Run separately:

```powershell
cd backend
mvn -q clean test
mvn -q clean compile -DskipTests
cd ..
git diff --check
```

Scan every modified and untracked text file for UTF-8/UTF-16 BOM. Expected: all Maven commands and `git diff --check` exit `0`; BOM list is empty.

- [x] **Step 2: Restart the backend and verify port 8082**

Stop only the verified ClipFetch Java process currently listening on port 8082. Start:

```powershell
$env:SERVER_PORT='8082'; mvn spring-boot:run
```

Verify `Get-NetTCPConnection -LocalPort 8082 -State Listen` reports a new Java PID. Record the real PID and startup result; do not print `.env` values.

- [x] **Step 3: Mark the plan and replace `HANDOFF.md`**

Mark completed steps in this plan. Replace `HANDOFF.md` with the actual changed files, RED/GREEN/full verification exit codes, listener PID, and the explicit boundary that no throughput gain was measured without an unfinished comparable Native task. Do not create a commit.
