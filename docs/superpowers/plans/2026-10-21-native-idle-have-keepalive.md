# Native Idle HAVE Keepalive Implementation Plan

> For agentic workers: Execute this plan inline in the current workspace. Do not create a commit unless the user explicitly requests one.

**Goal:** Prevent an idle Native Peer connection from being closed before a buffered HAVE message can make it eligible for unfinished work.

**Architecture:** Keep the existing PeerRoundConnectionPool.runMailbox() loop and incremental frame reader. Add a package-private, unit-tested retirement predicate, consume buffered/partial input before applying the existing three-second no-work exit, and restart the loop after a complete control frame so the cached availability revision is rebuilt.

**Tech Stack:** Java 21, JUnit 5, Maven, Spring Boot Native BitTorrent engine.

---

### Task 1: Add the failing idle-retirement regression tests

**Files:**
- Modify: backend/src/test/java/cc/ivera/magnet/engine/piece/PeerRoundConnectionPoolTest.java

- [x] Step 1: Add three focused predicate tests

Add java.time.Duration to the imports and add these tests to
PeerRoundConnectionPoolTest:

~~~java
@Test
void queuedInputKeepsIdlePeerAliveWhenCachedAvailabilityHasNoWork() {
    assertFalse(PeerRoundConnectionPool.shouldExitIdleMailbox(
            Duration.ofSeconds(4).toNanos(),false,true,false));
}

@Test
void partialInputKeepsIdlePeerAliveWhenCachedAvailabilityHasNoWork() {
    assertFalse(PeerRoundConnectionPool.shouldExitIdleMailbox(
            Duration.ofSeconds(4).toNanos(),false,false,true));
}

@Test
void idlePeerExitsOnlyWhenNoWorkOrInputRemain() {
    assertTrue(PeerRoundConnectionPool.shouldExitIdleMailbox(
            Duration.ofSeconds(4).toNanos(),false,false,false));
}
~~~

Use static imports for assertFalse and assertTrue alongside the existing
assertions.

- [x] Step 2: Run the focused test and verify RED

Run from backend:

~~~powershell
mvn -q "-Dtest=PeerRoundConnectionPoolTest" test
~~~

Expected result: the test compilation fails because
PeerRoundConnectionPool.shouldExitIdleMailbox is not yet defined. Do not
change the test to make this failure disappear.

### Task 2: Implement the minimal idle-input ordering fix

**Files:**
- Modify: backend/src/main/java/cc/ivera/magnet/engine/piece/PeerRoundConnectionPool.java

- [x] Step 1: Add the package-private retirement predicate

Near adaptiveNoProgressTimeoutNanos, add:

~~~java
static boolean shouldExitIdleMailbox(long idleNanos,
                                     boolean hasPotentialWork,
                                     boolean hasQueuedInput,
                                     boolean hasPartialFrame) {
    return idleNanos>Duration.ofSeconds(3).toNanos() &&
            !hasPotentialWork && !hasQueuedInput && !hasPartialFrame;
}
~~~

This preserves the existing three-second threshold and adds only the two
input guards needed by the regression.

- [x] Step 2: Consume buffered input before deciding to retire

Replace the idle branch beginning at mailbox.peerOutstanding(peer)==0 with
the following logic while preserving the existing wait and interruption
handling:

~~~java
if(mailbox.peerOutstanding(peer)==0) {
    boolean queuedInput=link.in.available()>0;
    if(queuedInput) {
        var ctrl=incremental.poll(link.in);
        if(ctrl!=null) {
            updateAdvertised(link,ctrl,planner.pieceCount());
            idleSince=System.nanoTime();
            continue;
        }
    }
    if(shouldExitIdleMailbox(System.nanoTime()-idleSince,
            mailbox.hasPotentialWork(peer,currentAvailability),queuedInput,
            incremental.hasPartialFrame()))break;
    try {
        mailbox.awaitStateChange(peer,TimeUnit.MILLISECONDS.toNanos(15));
    }catch(InterruptedException stopped) {
        Thread.currentThread().interrupt();
        throw new IOException("Multi-Piece actor interrupted",stopped);
    }
    continue;
}
~~~

The continue after a complete frame ensures the next iteration observes the
new advertisedRevision and rebuilds cachedAvailability. The existing
incremental reader retains a partial frame for the next poll.

- [x] Step 3: Run the focused test and verify GREEN

Run:

~~~powershell
mvn -q "-Dtest=PeerRoundConnectionPoolTest" test
~~~

Expected result: all PeerRoundConnectionPoolTest tests pass.

### Task 3: Run focused Native regressions

**Files:** None.

- [x] Step 1: Run the focused Native test set

Run from backend:

~~~powershell
mvn -q "-Dtest=PeerBlockMailboxTest,PeerHealthTrackerTest,PeerRoundConnectionPoolTest" test
~~~

Expected result: all tests pass with no failures or errors.

### Task 4: Run repository verification and restart the backend

**Files:**
- Modify: HANDOFF.md by replacing it with the actual completion record.

- [x] Step 1: Run full test and compile verification

Run:

~~~powershell
mvn -q clean test
mvn -q clean compile -DskipTests
~~~

Expected result: both commands exit 0.

- [x] Step 2: Run whitespace, BOM, and status checks

Run git diff --check, scan every changed text file for an initial UTF-8 BOM,
and inspect git status --short. Expected result: no whitespace errors, no BOM
in changed text files, and no unrelated files removed or overwritten.

- [x] Step 3: Restart and verify backend port 8082

Stop only the Java process currently verified as owning port 8082, start the
backend from backend with SERVER_PORT=8082; mvn spring-boot:run, and verify
that exactly one Java listener for cc.ivera.ClipFetchApplication owns 8082.
Do not start, resume, or modify any Native download task.

- [x] Step 4: Replace HANDOFF.md with real results

Record the exact changed files, RED/GREEN/focused/full/compile exit codes,
restart/listener verification, and the remaining boundary that no comparable
unfinished download benchmark was available. Do not report a speed percentage.
