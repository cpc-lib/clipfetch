# Native Peer Availability Single-Pass Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the duplicate Piece availability traversal inside `PeerHealthTracker.leaseExclusive()` while preserving Peer selection, fallback, cooldown, and lease-count semantics.

**Architecture:** Add a package-private three-state read method to the immutable `PeerPieceAvailability` snapshot. Change the private exclusive-lease scan to derive both “can serve” and “confirmed” from that one traversal for each candidate Peer; unknown Peers remain immediately eligible without a confirmed bonus. Keep all public behavior, synchronization, scoring, and call sites unchanged.

**Tech Stack:** Java 21, Spring Boot Native BitTorrent engine, JUnit 5, Maven, PowerShell.

This plan does not include a commit step because the repository instructions prohibit agents from creating commits unless explicitly requested.

---

## File map

- `backend/src/test/java/cc/ivera/magnet/engine/piece/PeerHealthTrackerTest.java` — lock down the single-iterator regression and the confirmed-over-unknown ranking behavior.
- `backend/src/main/java/cc/ivera/magnet/engine/piece/PeerPieceAvailability.java` — expose a package-private read-only `UNKNOWN`/`ABSENT`/`PRESENT` state query without exposing the internal `BitSet` map.
- `backend/src/main/java/cc/ivera/magnet/engine/piece/PeerHealthTracker.java` — replace the two Piece loops in the private lease helper with one state loop; leave the rest of the lease algorithm unchanged.
- `HANDOFF.md` — replace with actual RED/GREEN, full verification, runtime, and measurement-boundary evidence after implementation.

### Task 1: Add regression tests for single-pass availability evaluation

**Files:**
- Modify: `backend/src/test/java/cc/ivera/magnet/engine/piece/PeerHealthTrackerTest.java`

- [x] **Step 1: Change the iterator-count expectation to the desired behavior**

In `failedPeerFallbackScansEachPeerOnce`, change only the expected count from `4` to `3`:

```java
        assertEquals(3, pieceIterations.get());
```

The custom `AbstractSet` counts `pieces.iterator()` calls. The target implementation performs one representative lookup plus one availability traversal per candidate Peer; the current implementation performs one representative lookup, one `couldHave` traversal per candidate, and one `confirmed` traversal for the selected fallback candidate.

- [x] **Step 2: Add the confirmed-over-unknown regression**

Add this test to `PeerHealthTrackerTest`:

```java
    @Test
    void confirmedPeerStillOutranksUnknownPeer() {
        var unknown = new InetSocketAddress("192.0.2.10", 6881);
        var confirmed = new InetSocketAddress("192.0.2.11", 6881);
        var bits = new java.util.BitSet();
        bits.set(1);
        var availability = new PeerPieceAvailability(2, Map.of(confirmed, bits));
        var health = new PeerHealthTracker(List.of(unknown, confirmed), () -> 0L, 8_000);

        assertEquals(confirmed,
                health.leaseExclusive(Set.of(0, 1), availability));
        health.release(confirmed);
    }
```

This verifies that an `ABSENT` Piece followed by a `PRESENT` Piece still gives the confirmed Peer the existing `20_000_000L` ranking bonus, while the unreported Peer remains `UNKNOWN` and eligible without that bonus.

- [x] **Step 3: Run the focused test and verify RED**

Run from `backend`:

```powershell
mvn -q "-Dtest=PeerHealthTrackerTest" test
```

Expected result before production changes: Exit Code `1`, with `failedPeerFallbackScansEachPeerOnce` failing because the current two-pass implementation reports `4` iterator calls instead of the new expected `3`. The confirmed-peer test should compile and preserve the existing ranking behavior.

### Task 2: Add the package-private availability state query

**Files:**
- Modify: `backend/src/main/java/cc/ivera/magnet/engine/piece/PeerPieceAvailability.java`

- [x] **Step 1: Add the state type and query without changing existing methods**

Add the following package-private members after `confirmed` (or alongside the other availability queries):

```java
    enum PieceState { UNKNOWN, ABSENT, PRESENT }

    PieceState state(InetSocketAddress peer,int piece) {
        if(piece<0 || piece>=pieces)
            throw new IllegalArgumentException("Invalid Piece");
        BitSet bits=known.get(peer);
        if(bits==null)return PieceState.UNKNOWN;
        return bits.get(piece)?PieceState.PRESENT:PieceState.ABSENT;
    }
```

The method must read the existing immutable snapshot only. Do not return `BitSet`, do not copy the map, and do not alter `couldHave` or `confirmed`; their existing callers retain their current semantics.

- [x] **Step 2: Run the focused test and verify the state API is present**

Run:

```powershell
mvn -q "-Dtest=PeerHealthTrackerTest" test
```

Expected result: Exit Code `1` still comes from the iterator-count assertion, not from a missing state method. This confirms the test remains RED for the production lease change.

### Task 3: Merge the two lease availability scans

**Files:**
- Modify: `backend/src/main/java/cc/ivera/magnet/engine/piece/PeerHealthTracker.java`

- [x] **Step 1: Replace only the duplicated Piece loops**

Inside the private `leaseExclusive(Set<Integer> pieces, Set<InetSocketAddress> tried, PeerPieceAvailability availability, Set<InetSocketAddress> excluded, boolean allowExcludedFallback)` helper, replace the current `canServe` loop followed by the separate `confirmed` loop with:

```java
            boolean canServe=false;
            boolean confirmed=false;
            for(int piece:pieces) {
                if(availability==null) {
                    canServe=true;
                    break;
                }
                var state=availability.state(peer,piece);
                if(state==PeerPieceAvailability.PieceState.UNKNOWN) {
                    canServe=true;
                    break;
                }
                if(state==PeerPieceAvailability.PieceState.PRESENT) {
                    canServe=true;
                    confirmed=true;
                    break;
                }
            }
            if(!canServe)continue;
```

Then keep the existing rank calculation and replace only its confirmed-bonus block with:

```java
            if(confirmed)rank+=20_000_000L;
```

Do not change the `tried`, cooldown, `inFlight`, excluded/fallback, preferred-thread, performance-score, selection, or final `inFlight++` logic. When `availability == null`, the single loop still marks the Peer serviceable and leaves `confirmed` false, matching the old behavior. When a Peer is `UNKNOWN`, stopping is safe because no Piece in that Peer’s snapshot can produce a confirmed bonus.

- [x] **Step 2: Run the focused test and verify GREEN**

Run:

```powershell
mvn -q "-Dtest=PeerHealthTrackerTest" test
```

Expected result: Exit Code `0`; the single-pass iterator regression expects `3`, the confirmed-over-unknown test passes, and all existing health/wakeup/fallback tests remain green.

### Task 4: Run Native regression and repository verification

**Files:**
- No additional production files.

- [x] **Step 1: Run related Native tests**

Run from `backend`:

```powershell
mvn -q "-Dtest=PeerHealthTrackerTest,PeerBlockMailboxTest,PeerRoundConnectionPoolTest,PeerIncrementalFrameReaderTest,NativeWorkerJobManagerTest" test
```

Expected: Exit Code `0` with no failures or errors.

- [x] **Step 2: Run clean full tests and compile**

Run serially, not concurrently, to avoid Windows `target` file-lock races:

```powershell
mvn -q clean test
mvn -q clean compile -DskipTests
mvn -q test
```

Expected: all three commands Exit Code `0`. Record the actual Surefire totals; do not copy expected counts if the repository reports different totals.

- [x] **Step 3: Check diff and text encoding**

Run from the repository root:

```powershell
git diff --check
```

Then scan all tracked and untracked text files under the repository for UTF-8 BOM bytes `EF BB BF`, `FF FE`, or `FE FF`. Expected: `git diff --check` Exit Code `0` except for any already documented line-ending warning, and zero BOM files.

- [x] **Step 4: Restart and verify the backend listener**

Stop the current Maven/Java process, start the backend with `SERVER_PORT=8082`, wait for `Started ClipFetchApplication`, and run:

```powershell
Get-NetTCPConnection -LocalPort 8082 -State Listen
```

Expected: the new backend starts successfully and exactly one process owns the 8082 listener. Do not restore, create, or modify a Native download task during this check.

### Task 5: Update handoff with evidence and boundaries

**Files:**
- Replace: `HANDOFF.md`

- [x] **Step 1: Record actual implementation and verification results**

Replace the handoff with the real changed files, RED/GREEN commands and exit codes, related/full test results, compile result, diff/BOM checks, backend PID/log status, and the fact that no user magnet task was touched.

- [x] **Step 2: Preserve the measurement boundary**

State that the change removes duplicate lock-held Piece traversal and preserves lease semantics, but do not report a download-speed percentage because there is still no comparable unfinished Native download benchmark. Record the next step as collecting a controlled `native_perf`/`native_multiplexed_diagnostics` sample when one is available.

## Self-review

- **Spec coverage:** The plan covers the three-state query, one traversal per candidate, preserved unknown/confirmed semantics, unchanged fallback/ranking/in-flight behavior, focused regressions, full verification, restart, and the no-speed-claim boundary.
- **Placeholder scan:** The plan contains no unresolved placeholder or deferred step.
- **Type consistency:** The package-private `PeerPieceAvailability.PieceState` and `state(...)` method are introduced before `PeerHealthTracker` consumes them; the test uses the existing package and imports, and all existing lease overloads remain unchanged.
