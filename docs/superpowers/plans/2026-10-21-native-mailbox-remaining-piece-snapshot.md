# Native Mailbox Remaining-Piece Snapshot Implementation Plan

> **For agentic workers:** Execute this plan inline in the current workspace. Do not create a commit unless the user explicitly requests one.

**Goal:** Remove repeated allocation and full-map scanning from `PeerBlockMailbox.remainingPieces()` while preserving its synchronized API, Piece ordering, and delivery semantics.

**Architecture:** Keep `remainingByPiece` as the Block-count source of truth. Add a mailbox-local insertion-ordered Piece index plus an immutable snapshot; initialize it once and rebuild the snapshot only when `deliver()` completes the final Block of a Piece. `remainingPieces()` returns that immutable snapshot under the existing monitor.

**Tech Stack:** Java 21, JUnit 5, Maven, existing `PeerBlockMailbox` scheduler tests.

---

## Files and responsibilities

- Modify `backend/src/test/java/cc/ivera/magnet/engine/piece/PeerBlockMailboxTest.java`: add regression tests for stable immutable snapshots and refresh only after a Piece's final Block is accepted.
- Modify `backend/src/main/java/cc/ivera/magnet/engine/piece/PeerBlockMailbox.java`: maintain the Piece index/snapshot and return the cached immutable value from `remainingPieces()`.
- Reference `docs/superpowers/specs/2026-10-21-native-mailbox-remaining-piece-snapshot-design.md`: approved design specification; no changes required unless implementation exposes a contradiction.
- Create/update `HANDOFF.md`: record actual commands, exit codes, restart status, and remaining boundaries at the end of the round.

### Task 1: Add the mailbox regression tests

**Files:**
- Modify: `backend/src/test/java/cc/ivera/magnet/engine/piece/PeerBlockMailboxTest.java` imports and test methods near the existing mailbox unit tests.

- [ ] **Step 1: Add tests before touching production code**

Add the following static imports:

```java
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
```

Add these tests to `PeerBlockMailboxTest`:

```java
@Test
void remainingPiecesReturnsStableImmutableSnapshot() {
    var first=new PeerBlockMailbox.Key(0,0,PieceBlockPlanner.BLOCK_BYTES);
    var second=new PeerBlockMailbox.Key(1,0,PieceBlockPlanner.BLOCK_BYTES);
    var mailbox=new PeerBlockMailbox(List.of(first,second));

    var snapshot=mailbox.remainingPieces();

    assertEquals(List.of(0,1),new ArrayList<>(snapshot));
    assertSame(snapshot,mailbox.remainingPieces());
    assertThrows(UnsupportedOperationException.class,snapshot::clear);
}

@Test
void remainingPiecesRefreshesOnlyAfterPieceCompletes() throws Exception {
    var peer=new InetSocketAddress("192.0.2.30",6881);
    var first=new PeerBlockMailbox.Key(0,0,PieceBlockPlanner.BLOCK_BYTES);
    var second=new PeerBlockMailbox.Key(0,PieceBlockPlanner.BLOCK_BYTES,
            PieceBlockPlanner.BLOCK_BYTES);
    var third=new PeerBlockMailbox.Key(1,0,PieceBlockPlanner.BLOCK_BYTES);
    var mailbox=new PeerBlockMailbox(List.of(first,second,third));
    var availability=new PeerPieceAvailability(2,Map.of());

    assertEquals(3,mailbox.claim(peer,availability,0,16).size());
    var initial=mailbox.remainingPieces();

    mailbox.deliver(peer,first,new byte[first.length()]);
    assertEquals(List.of(0,1),new ArrayList<>(mailbox.remainingPieces()));
    assertSame(initial,mailbox.remainingPieces());

    mailbox.deliver(peer,second,new byte[second.length()]);
    var afterPiece=mailbox.remainingPieces();
    assertEquals(List.of(1),new ArrayList<>(afterPiece));
    assertNotSame(initial,afterPiece);

    mailbox.deliver(peer,third,new byte[third.length()]);
    assertTrue(mailbox.remainingPieces().isEmpty());
}
```

- [ ] **Step 2: Run the focused test to verify the expected RED failure**

Run from `D:\release\clipfetch\backend`:

```powershell
mvn -q "-Dtest=PeerBlockMailboxTest" test
```

Expected result: Exit Code 1. The new stable-snapshot assertion fails because the current `remainingPieces()` creates a new `HashSet` for every call. This failure must occur before any production implementation change.

### Task 2: Implement the cached immutable snapshot

**Files:**
- Modify: `backend/src/main/java/cc/ivera/magnet/engine/piece/PeerBlockMailbox.java:68-143, 364-369`.

- [ ] **Step 1: Add the mailbox-local index and snapshot fields**

Immediately beside `remainingByPiece`, add:

```java
private final LinkedHashSet<Integer> remainingPieceIndexes=new LinkedHashSet<>();
private Set<Integer> remainingPieceSnapshot=Set.of();
```

- [ ] **Step 2: Initialize the index and snapshot in the constructor**

Inside the existing `wanted` loop, before updating the Block count, add the Piece index:

```java
remainingPieceIndexes.add(key.piece());
remainingByPiece.merge(key.piece(),1,Integer::sum);
```

After the loop, initialize the first immutable snapshot:

```java
refreshRemainingPieceSnapshot();
```

- [ ] **Step 3: Return the cached value and define the refresh helper**

Replace the body of `remainingPieces()` with:

```java
synchronized Set<Integer> remainingPieces(){
    return remainingPieceSnapshot;
}
```

Add this private helper near the mailbox private helpers:

```java
private void refreshRemainingPieceSnapshot() {
    remainingPieceSnapshot=Collections.unmodifiableSet(
            new LinkedHashSet<>(remainingPieceIndexes));
}
```

The copy preserves first-seen Piece order while the unmodifiable wrapper prevents callers from mutating mailbox state.

- [ ] **Step 4: Refresh only when the final Block of a Piece is accepted**

Replace the existing `remainingByPiece.merge` line in `deliver()` with:

```java
int remaining=remainingByPiece.merge(key.piece(),-1,Integer::sum);
if(remaining==0) {
    remainingPieceIndexes.remove(key.piece());
    refreshRemainingPieceSnapshot();
}
```

Do not change `hasPotentialWork()`, lease selection, `disconnect()`, request windows, or any network behavior.

### Task 3: Run the GREEN and regression suites

**Files:**
- No additional files.

- [ ] **Step 1: Re-run the focused mailbox test**

Run from `D:\release\clipfetch\backend`:

```powershell
mvn -q "-Dtest=PeerBlockMailboxTest" test
```

Expected result: Exit Code 0; all mailbox tests pass, including the two new snapshot tests.

- [ ] **Step 2: Run the related Native regression tests**

```powershell
mvn -q "-Dtest=PeerBlockMailboxTest,PeerHealthTrackerTest,PeerRoundConnectionPoolTest" test
```

Expected result: Exit Code 0.

- [ ] **Step 3: Run the full backend test suite and clean compile**

```powershell
mvn -q clean test
mvn -q clean compile -DskipTests
```

Expected result: both commands Exit Code 0. Do not report a speed percentage; no comparable unfinished Native download benchmark is available.

### Task 4: Verify the change and restart the backend

**Files:**
- Update: `HANDOFF.md` with real results only.

- [ ] **Step 1: Check the diff and text encoding**

Run from `D:\release\clipfetch`:

```powershell
git diff --check
```

Then scan all changed text files for UTF-8 BOM bytes (`EF BB BF`) and record the actual result in `HANDOFF.md`.

- [ ] **Step 2: Restart only the current ClipFetch backend listener**

Before restarting, identify the process listening on TCP port 8082. Stop only that verified listener process, start `mvn.cmd spring-boot:run` from `backend` with `SERVER_PORT=8082`, and verify exactly one Java listener remains on 8082. Record the actual PID and command result in `HANDOFF.md`.

- [ ] **Step 3: Update the handoff**

Replace `HANDOFF.md` as a complete current-round record containing:

- the two modified production/test files and the new plan/spec files;
- RED, GREEN, focused, full-test, clean-compile, diff, BOM, and restart results with real Exit Codes;
- the fact that no Native task was resumed and no speed percentage was measured;
- any remaining limitation or next optimization candidate.

No commit is created unless the user explicitly asks for one.
