# Native Mailbox Unclaimed-Block Index Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reduce synchronized mailbox scheduling scans by indexing unfinished Blocks that have no primary lease by Piece.

**Architecture:** Keep `PeerBlockMailbox` as the single synchronized scheduler. Add an insertion-ordered `Piece -> LinkedHashSet<Block>` index for fresh primary candidates, update it at every primary-lease lifecycle transition, and use it only for fresh claims. Preserve the existing `unfinished` scan for end-game backups and overdue transfers, whose candidate sets are already bounded or throttled.

**Tech Stack:** Java 21, JUnit 5, Maven, Spring Boot backend.

---

## File map

- Modify: `backend/src/main/java/cc/ivera/magnet/engine/piece/PeerBlockMailbox.java` — maintain the fresh-primary index and use Piece buckets during normal claims.
- Modify: `backend/src/test/java/cc/ivera/magnet/engine/piece/PeerBlockMailboxTest.java` — add a RED/GREEN regression test for availability checks and lifecycle tests for index consistency.
- Create: `docs/superpowers/specs/2026-10-21-native-mailbox-unclaimed-index-design.md` — approved design; already written.
- Create: `docs/superpowers/plans/2026-10-21-native-mailbox-unclaimed-index.md` — this plan.
- Modify: `HANDOFF.md` — replace the handoff with the actual final progress and verification results after implementation.

### Task 1: Write and verify the failing mailbox test

**Files:**
- Modify: `backend/src/test/java/cc/ivera/magnet/engine/piece/PeerBlockMailboxTest.java`

- [x] **Step 1: Add a test that defines the scheduling-scan contract.**

Add imports for `BitSet` and `ArrayList` (the existing `assertEquals` import is retained), then add this test and nested test BitSet to `PeerBlockMailboxTest`:

```java
    @Test
    void claimChecksKnownUnavailablePieceOncePerPieceBucket() {
        var peer=new InetSocketAddress("192.0.2.12",6881);
        int pieces=128;
        int blocksPerPiece=PeerBlockMailbox.MAX_BLOCKS/pieces;
        var keys=new ArrayList<PeerBlockMailbox.Key>(PeerBlockMailbox.MAX_BLOCKS);
        for(int piece=0;piece<pieces;piece++)
            for(int block=0;block<blocksPerPiece;block++)
                keys.add(new PeerBlockMailbox.Key(piece,
                        block*PieceBlockPlanner.BLOCK_BYTES,
                        PieceBlockPlanner.BLOCK_BYTES));

        var emptyBitfield=new CountingBitSet();
        var availability=new PeerPieceAvailability(pieces,Map.of(peer,emptyBitfield));
        var mailbox=new PeerBlockMailbox(keys,128);

        assertTrue(mailbox.claim(peer,availability,0,128).isEmpty());
        assertEquals(pieces,emptyBitfield.getCalls());
    }

    private static final class CountingBitSet extends BitSet {
        private int getCalls;

        @Override public boolean get(int bitIndex) {
            getCalls++;
            return super.get(bitIndex);
        }

        @Override public Object clone() { return this; }

        int getCalls() { return getCalls; }
    }
```

The test deliberately uses the maximum mailbox size and a known-empty Peer
bitfield. The desired implementation checks availability once for each Piece
bucket instead of once for every Block.

- [x] **Step 2: Run the focused test and verify the expected RED result.**

Run from `D:\\release\\clipfetch\\backend`:

```powershell
mvn -q "-Dtest=PeerBlockMailboxTest#claimChecksKnownUnavailablePieceOncePerPieceBucket" test
```

Expected result before production changes: exit code `1`, with the assertion
reporting `expected: <128> but was: <32768>`. If the test fails for compilation,
repair only the test imports or syntax and rerun until it fails on that count.

### Task 2: Add the Piece-bucket index and preserve lifecycle invariants

**Files:**
- Modify: `backend/src/main/java/cc/ivera/magnet/engine/piece/PeerBlockMailbox.java`

- [x] **Step 1: Add the index and constructor population.**

Add this field next to `unfinished`:

```java
    private final Map<Integer,LinkedHashSet<Block>> unclaimedByPiece=new LinkedHashMap<>();
```

In the constructor, after adding the new Block to `unfinished`, add it to its
Piece bucket before incrementing `remainingByPiece` and `unclaimed`:

```java
            var block=blocks.get(key);
            unfinished.add(block);
            unclaimedByPiece.computeIfAbsent(key.piece(),ignored->new LinkedHashSet<>())
                    .add(block);
```

Keep the existing `remainingByPiece` and `unclaimed` updates unchanged.

- [x] **Step 2: Replace the normal-claim full scans with bucket scans.**

Replace only the `leadPiece` discovery loop in `claim()` with a Piece-bucket
loop. It must skip a known-false Piece before iterating its Blocks:

```java
        int leadPiece=-1;
        if(unclaimed>0) {
            for(var entry:unclaimedByPiece.entrySet()) {
                int piece=entry.getKey();
                if(!available.couldHave(peer,piece))continue;
                for(var block:entry.getValue()) {
                    if(!block.completed && block.primary==null &&
                            !block.priorLeases.contains(peer)) {
                        leadPiece=piece;
                        break;
                    }
                }
                if(leadPiece>=0)break;
            }
        }
```

Replace `claimPrimary(...)` with a helper that iterates only the selected
Piece bucket for `lead=true`, or each non-lead bucket for `lead=false`:

```java
    private void claimPrimary(InetSocketAddress peer,PeerPieceAvailability available,
                              long nowNanos,int slots,int leadPiece,boolean lead,
                              int quota,List<Request> result) {
        if(lead) {
            claimPrimaryFromBucket(peer,nowNanos,slots,quota,
                    unclaimedByPiece.get(leadPiece),result);
            return;
        }
        for(var entry:unclaimedByPiece.entrySet()) {
            if(result.size()>=slots || result.size()>=quota)break;
            if(entry.getKey()==leadPiece ||
                    !available.couldHave(peer,entry.getKey()))continue;
            claimPrimaryFromBucket(peer,nowNanos,slots,quota,
                    entry.getValue(),result);
        }
    }

    private void claimPrimaryFromBucket(InetSocketAddress peer,long nowNanos,
                                        int slots,int quota,
                                        LinkedHashSet<Block> candidates,
                                        List<Request> result) {
        if(candidates==null)return;
        var iterator=candidates.iterator();
        while(iterator.hasNext() && result.size()<slots && result.size()<quota) {
            var block=iterator.next();
            if(block.completed || block.primary!=null) {
                iterator.remove();
                continue;
            }
            if(block.priorLeases.contains(peer))continue;
            iterator.remove();
            block.primary=new Lease(peer,nowNanos);
            block.priorLeases.add(peer);
            unclaimed--;
            outstanding.merge(peer,1,Integer::sum);
            result.add(new Request(block.key,peer,false));
        }
    }
```

The `available` parameter remains on `claimPrimary` because non-lead bucket
selection must skip known-false Pieces. The helper intentionally does not
re-check availability per Block: all Blocks in one bucket share the same Piece
index and the passed availability snapshot is immutable.

- [x] **Step 3: Maintain the index on primary disconnect.**

In `disconnect()`, when a disconnected primary has no backup and the Block is
returned to the fresh-work pool, add it back to its Piece bucket beside the
existing `unclaimed++`:

```java
                if(block.primary==null) {
                    unclaimed++;
                    unclaimedByPiece.computeIfAbsent(block.key.piece(),
                            ignored->new LinkedHashSet<>()).add(block);
                }
```

Do not change the backup-promotion branch, `reassignOverdue()`, cancellation
behavior, or outstanding counters.

### Task 3: Run the focused test and add lifecycle regression coverage

**Files:**
- Modify: `backend/src/test/java/cc/ivera/magnet/engine/piece/PeerBlockMailboxTest.java`

- [x] **Step 1: Run the RED test after the minimal implementation.**

Run:

```powershell
mvn -q "-Dtest=PeerBlockMailboxTest#claimChecksKnownUnavailablePieceOncePerPieceBucket" test
```

Expected result after Task 2: exit code `0`.

- [x] **Step 2: Retain the existing disconnect lifecycle coverage.**

The existing disconnect wakeup test verifies that a primary disconnect returns
Blocks to the claimable path, including the new index. Existing delivery,
duplicate, and stale-response tests are outside this index-only change.

- [x] **Step 3: Run all focused mailbox/health/connection tests.**

Run from `D:\\release\\clipfetch\\backend`:

```powershell
mvn -q "-Dtest=PeerBlockMailboxTest,PeerHealthTrackerTest,PeerRoundConnectionPoolTest" test
```

Expected result: exit code `0`, with no test failures.

### Task 4: Full verification and handoff

**Files:**
- Modify: `HANDOFF.md`

- [x] **Step 1: Run the full backend test suite.**

```powershell
mvn -q clean test
```

Expected result: exit code `0` and no failed tests.

- [x] **Step 2: Run the clean compile verification.**

```powershell
mvn -q clean compile -DskipTests
```

Expected result: exit code `0`.

- [x] **Step 3: Check the diff and text encoding.**

From `D:\\release\\clipfetch`:

```powershell
git diff --check
$textFiles=@(
  'backend/src/main/java/cc/ivera/magnet/engine/piece/PeerBlockMailbox.java',
  'backend/src/test/java/cc/ivera/magnet/engine/piece/PeerBlockMailboxTest.java',
  'docs/superpowers/specs/2026-10-21-native-mailbox-unclaimed-index-design.md',
  'docs/superpowers/plans/2026-10-21-native-mailbox-unclaimed-index.md',
  'HANDOFF.md'
)
foreach($path in $textFiles) {
  $bytes=[System.IO.File]::ReadAllBytes((Join-Path (Get-Location) $path))
  if($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF) {
    throw "BOM detected: $path"
  }
}
```

Expected result: `git diff --check` exits `0`, and the PowerShell scan prints
no BOM error.

- [x] **Step 4: Replace `HANDOFF.md` with factual round status.**

Use `apply_patch` to replace the complete file with: the mailbox index goal,
the exact files changed, the exact commands and exit codes from Steps 1–3,
whether the existing backend process was restarted, the fact that no live
unfinished magnet benchmark was run, and the next remaining step. Do not claim
a download-speed percentage or production readiness without that benchmark.

- [x] **Step 5: Inspect the final diff.**

Run:

```powershell
git status --short
git diff --stat
git diff -- backend/src/main/java/cc/ivera/magnet/engine/piece/PeerBlockMailbox.java backend/src/test/java/cc/ivera/magnet/engine/piece/PeerBlockMailboxTest.java
```

Confirm every changed line maps to the approved mailbox-index design and leave
all unrelated existing modifications untouched. Do not create a commit because
the repository instructions explicitly prohibit proactive commits.
