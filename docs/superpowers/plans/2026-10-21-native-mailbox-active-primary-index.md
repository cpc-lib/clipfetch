# Native Mailbox Active-Primary Index Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task with review checkpoints. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make overdue Native BitTorrent lease reassignment scan only Blocks with active primary leases instead of scanning every unfinished Block.

**Architecture:** Keep the existing synchronized `PeerBlockMailbox` and add one insertion-ordered `activePrimary` set. A Block enters the set when a fresh primary lease is assigned, leaves when the Block is accepted or becomes unclaimed after a primary disconnect without a backup, and remains indexed through backup promotion and overdue primary reassignment. `reassignOverdue()` changes only its iteration source; all timeout, eligibility, cancellation, and Peer-health behavior stays unchanged.

**Tech Stack:** Java 21, Spring Boot 3.5.4, JUnit 5, Maven, PowerShell.

**Execution constraint:** Do not create a commit. The repository instructions require leaving commits to the user unless explicitly requested.

---

## File map

- Modify `backend/src/test/java/cc/ivera/magnet/engine/piece/PeerBlockMailboxTest.java` to add deterministic lifecycle tests for the package-private scheduler index. Tests use reflection so production code does not gain a diagnostic method.
- Modify `backend/src/main/java/cc/ivera/magnet/engine/piece/PeerBlockMailbox.java` to maintain `activePrimary` and use it for overdue reassignment.
- Modify `backend/src/main/java/cc/ivera/magnet/engine/piece/PeerRoundConnectionPool.java` only to update the nearby complexity comment from `unfinished` to active primary leases.
- Update `HANDOFF.md` after implementation with actual commands, exit codes, runtime state, and remaining benchmark boundaries.
- Do not modify `.env`, `application.yml`, controllers, database migrations, frontend code, or public APIs.

## Task 1: Add failing active-primary lifecycle tests

**Files:**

- Modify: `backend/src/test/java/cc/ivera/magnet/engine/piece/PeerBlockMailboxTest.java`

- [x] **Step 1: Add a reflection helper and the fresh/completion/disconnect test.**

Add this helper inside `PeerBlockMailboxTest`:

```java
    private static java.util.Set<?> activePrimary(PeerBlockMailbox mailbox)
            throws ReflectiveOperationException {
        var field=PeerBlockMailbox.class.getDeclaredField("activePrimary");
        field.setAccessible(true);
        return (java.util.Set<?>)field.get(mailbox);
    }
```

Add this test:

```java
    @Test
    void activePrimaryIndexTracksFreshCompletionAndDisconnect() throws Exception {
        var firstPeer=new InetSocketAddress("192.0.2.20",6881);
        var secondPeer=new InetSocketAddress("192.0.2.21",6881);
        var availability=new PeerPieceAvailability(1,Map.of());
        var firstKey=new PeerBlockMailbox.Key(0,0,PieceBlockPlanner.BLOCK_BYTES);
        var secondKey=new PeerBlockMailbox.Key(0,PieceBlockPlanner.BLOCK_BYTES,
                PieceBlockPlanner.BLOCK_BYTES);
        var mailbox=new PeerBlockMailbox(List.of(firstKey,secondKey),16);

        assertEquals(0,activePrimary(mailbox).size());
        assertEquals(2,mailbox.claim(firstPeer,availability,0,16).size());
        assertEquals(2,activePrimary(mailbox).size());

        mailbox.deliver(firstPeer,firstKey,new byte[firstKey.length()]);
        assertEquals(1,activePrimary(mailbox).size());

        mailbox.disconnect(firstPeer);
        assertEquals(0,activePrimary(mailbox).size());
        assertEquals(1,mailbox.claim(secondPeer,availability,1,16).size());
        assertEquals(1,activePrimary(mailbox).size());
    }
```

- [x] **Step 2: Add backup-promotion and overdue-transfer tests.**

Add these tests:

```java
    @Test
    void primaryDisconnectPromotesBackupWithoutRemovingActiveIndex() throws Exception {
        var primary=new InetSocketAddress("192.0.2.22",6881);
        var backup=new InetSocketAddress("192.0.2.23",6881);
        var thirdPeer=new InetSocketAddress("192.0.2.24",6881);
        var availability=new PeerPieceAvailability(1,Map.of());
        var key=new PeerBlockMailbox.Key(0,0,PieceBlockPlanner.BLOCK_BYTES);
        var mailbox=new PeerBlockMailbox(List.of(key),16);

        assertEquals(1,mailbox.claim(primary,availability,0,16).size());
        assertEquals(1,mailbox.claim(backup,availability,2_000_000_000L,16).size());
        assertEquals(1,activePrimary(mailbox).size());

        mailbox.disconnect(primary);
        assertEquals(1,activePrimary(mailbox).size());

        mailbox.disconnect(backup);
        assertEquals(0,activePrimary(mailbox).size());
        assertEquals(1,mailbox.claim(thirdPeer,availability,3_000_000_000L,16).size());
        assertEquals(1,activePrimary(mailbox).size());
    }

    @Test
    void overdueReassignmentKeepsTransferredBlockInActiveIndex() throws Exception {
        var primary=new InetSocketAddress("192.0.2.25",6881);
        var receiver=new InetSocketAddress("192.0.2.26",6881);
        var availability=new PeerPieceAvailability(1,Map.of());
        var keys=new ArrayList<PeerBlockMailbox.Key>();
        for(int block=0;block<17;block++)
            keys.add(new PeerBlockMailbox.Key(0,
                    block*PieceBlockPlanner.BLOCK_BYTES,
                    PieceBlockPlanner.BLOCK_BYTES));
        var mailbox=new PeerBlockMailbox(keys,16);

        assertEquals(16,mailbox.claim(primary,availability,0,16).size());
        assertEquals(16,activePrimary(mailbox).size());
        assertEquals(1,mailbox.reassignOverdue(receiver,availability,
                12_000_000_000L,1).size());
        assertEquals(16,activePrimary(mailbox).size());
    }
```

- [x] **Step 3: Run the focused test before implementation.**

Run from `D:\release\clipfetch\backend`:

```powershell
mvn -q "-Dtest=PeerBlockMailboxTest" test
```

Expected result: **FAIL** because the current production class has no
`activePrimary` field and the reflection helper reports
`NoSuchFieldException: activePrimary`. Do not change production code until
this RED result is observed.

## Task 2: Implement the minimal active-primary index

**Files:**

- Modify: `backend/src/main/java/cc/ivera/magnet/engine/piece/PeerBlockMailbox.java`
- Modify: `backend/src/main/java/cc/ivera/magnet/engine/piece/PeerRoundConnectionPool.java`

- [x] **Step 1: Add the index beside the existing mailbox indexes.**

Immediately after `unfinished`, add:

```java
    private final LinkedHashSet<Block> activePrimary=new LinkedHashSet<>();
```

Do not populate it in the constructor: all constructor-created Blocks start
without a primary lease.

- [x] **Step 2: Add and remove entries at the primary-lease transitions.**

In `claimPrimaryFromBucket`, immediately after assigning the primary lease,
add the Block before updating outstanding state:

```java
            block.primary=new Lease(peer,nowNanos);
            block.priorLeases.add(peer);
            activePrimary.add(block);
            unclaimed--;
```

In `deliver`, remove the accepted Block from the active-primary index next to
the existing `unfinished.remove(entry)` call:

```java
        unfinished.remove(entry);
        activePrimary.remove(entry);
```

In `disconnect`, remove a Block only when the primary lease becomes null. Keep
the index unchanged when a backup is promoted:

```java
                block.primary=block.backup;
                block.backup=null;
                if(block.primary==null) {
                    activePrimary.remove(block);
                    unclaimed++;
                    unclaimedByPiece.computeIfAbsent(block.key.piece(),
                            ignored->new LinkedHashSet<>()).add(block);
                }
```

- [x] **Step 3: Restrict overdue reassignment to active primary leases.**

Change only the loop source in `reassignOverdue`:

```java
        for(var block:activePrimary) {
```

Do not remove or add entries inside this loop. A reassignment replaces one
primary lease with another, so the Block remains in the same index.

- [x] **Step 4: Update the scheduler complexity comment.**

In `PeerRoundConnectionPool.runMailbox`, replace the stale comment:

```java
                        // An overdue scan is O(active primary leases). Avoid
                        // rescanning from each 2ms actor poll at 64+ peers.
```

Leave the 250 ms check interval and all scheduling behavior unchanged.

- [x] **Step 5: Run the focused tests after implementation.**

Run:

```powershell
mvn -q "-Dtest=PeerBlockMailboxTest" test
```

Expected result: **PASS**, including the three new lifecycle tests and the
existing mailbox tests.

## Task 3: Run focused regression verification

**Files:** None.

- [x] **Step 1: Run the mailbox, health, and connection-pool regression set.**

From `D:\release\clipfetch\backend`, run:

```powershell
mvn -q "-Dtest=PeerBlockMailboxTest,PeerHealthTrackerTest,PeerRoundConnectionPoolTest" test
```

Expected result: **PASS**. This confirms the index does not alter mailbox
wakeup, Peer health leasing, or connection-pool behavior.

- [x] **Step 2: Inspect the production diff for scope and invariants.**

Run:

```powershell
git diff -- backend/src/main/java/cc/ivera/magnet/engine/piece/PeerBlockMailbox.java backend/src/main/java/cc/ivera/magnet/engine/piece/PeerRoundConnectionPool.java backend/src/test/java/cc/ivera/magnet/engine/piece/PeerBlockMailboxTest.java
```

Confirm the diff contains only the index field, its four lifecycle updates,
the `activePrimary` iteration source, the complexity-comment correction, and
the focused tests. If unrelated changes appear, leave them untouched and
report them in `HANDOFF.md`.

## Task 4: Full verification and handoff

**Files:**

- Modify: `HANDOFF.md`

- [x] **Step 1: Run the full test suite and clean compile.**

Run from `D:\release\clipfetch\backend`:

```powershell
mvn -q clean test
mvn -q clean compile -DskipTests
```

Expected result: both commands exit with code `0`.

- [x] **Step 2: Run repository hygiene checks.**

From `D:\release\clipfetch`, run:

```powershell
git diff --check
$paths=@(
  'HANDOFF.md',
  'docs/superpowers/specs/2026-10-21-native-mailbox-active-primary-index-design.md',
  'docs/superpowers/plans/2026-10-21-native-mailbox-active-primary-index.md',
  'backend/src/main/java/cc/ivera/magnet/engine/piece/PeerBlockMailbox.java',
  'backend/src/main/java/cc/ivera/magnet/engine/piece/PeerRoundConnectionPool.java',
  'backend/src/test/java/cc/ivera/magnet/engine/piece/PeerBlockMailboxTest.java'
)
foreach($path in $paths) {
    $bytes=[System.IO.File]::ReadAllBytes((Resolve-Path $path))
    if($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and
       $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF) {
        throw "BOM found: $path"
    }
}
```

Expected result: `git diff --check` exits `0` and the BOM loop completes
without throwing.

- [x] **Step 3: Restart the backend after Java changes.**

Verify the listener before stopping it, then restart with the required port:

```powershell
$listener=Get-NetTCPConnection -LocalPort 8082 -State Listen -ErrorAction SilentlyContinue
if($listener) { Stop-Process -Id $listener[0].OwningProcess -Force }
$env:SERVER_PORT='8082'
mvn spring-boot:run
```

Verify in a separate PowerShell prompt:

```powershell
Get-NetTCPConnection -LocalPort 8082 -State Listen
```

Expected result: one listener exists on port `8082`. Do not start or resume a
Native download task during this implementation verification.

- [x] **Step 4: Replace `HANDOFF.md` with the actual final state.**

Record the exact changed files, RED/GREEN and full verification exit codes,
the backend listener state, the fact that no download task was resumed, and
the remaining limitation that no comparable unfinished Native task exists for
a real throughput comparison. Do not claim a speed percentage.
