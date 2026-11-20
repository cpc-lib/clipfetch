# Native Lease Scan and Cooldown Boundary Single-Pass Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the duplicate availability traversal between Native exclusive Peer selection and cooldown-delay calculation while preserving lease selection, fallback, waiting, and `inFlight` semantics.

**Architecture:** Add a package-private `LeaseScanResult` containing the selected Peer and the earliest cooldown boundary. Add a combined scan used only by `awaitLeaseWithStatus()`; keep the existing non-waiting lease helper’s behavior and public/package-visible overloads unchanged. In the combined scan, read the clock once, scan each idle Peer’s Piece states once, calculate the delay before tried/failure/cooldown selection filters, then apply the existing scoring and controlled fallback rules.

**Tech Stack:** Java 21, Spring Boot 3.5.4, JUnit 5, Maven, PowerShell.

This plan does not include a commit step because repository instructions prohibit agents from creating commits unless explicitly requested.

---

## File map

- `backend/src/test/java/cc/ivera/magnet/engine/piece/PeerHealthTrackerTest.java` — add focused tests for the combined result and earliest cooldown boundary.
- `backend/src/main/java/cc/ivera/magnet/engine/piece/PeerHealthTracker.java` — add the package-private result/combined scan and route `awaitLeaseWithStatus()` through it without changing non-waiting lease behavior.
- `docs/superpowers/plans/2026-10-21-native-lease-scan-delay-single-pass.md` — record this approved implementation sequence.
- `HANDOFF.md` — replace with actual implementation, verification, runtime, and measurement-boundary evidence after the code change.

## Task 1: Add failing tests for the combined scan

**Files:**
- Modify: `backend/src/test/java/cc/ivera/magnet/engine/piece/PeerHealthTrackerTest.java`

- [x] **Step 1: Add a test that requires the combined result and counts one state traversal per idle Peer**

Add this test in the existing `cc.ivera.magnet.engine.piece` package:

```java
    @Test
    void combinedLeaseScanDoesNotRepeatAvailabilityTraversalForDelay() {
        var first = new InetSocketAddress("192.0.2.10", 6881);
        var second = new InetSocketAddress("192.0.2.11", 6881);
        var availability = new PeerPieceAvailability(1, Map.of(
                first, new java.util.BitSet(),
                second, new java.util.BitSet()));
        var health = new PeerHealthTracker(List.of(first, second), () -> 0L, 8_000);
        var pieceIterations = new AtomicInteger();
        Set<Integer> pieces = new AbstractSet<>() {
            @Override
            public Iterator<Integer> iterator() {
                pieceIterations.incrementAndGet();
                return Set.of(0).iterator();
            }

            @Override
            public int size() {
                return 1;
            }
        };

        var result = health.scanLease(pieces, availability, Set.of());

        assertNull(result.peer());
        assertEquals(Long.MAX_VALUE, result.nextLeaseDelayNanos());
        assertEquals(3, pieceIterations.get());
    }
```

The expected count is one representative Piece lookup plus one availability state traversal for each of the two Peers. The old code has no `scanLease` result API, so this test must fail at compilation before production code is added.

- [x] **Step 2: Add the earliest-cooldown result test**

Add this test:

```java
    @Test
    void combinedLeaseScanUsesEarliestIdleServingPeerCooldown() {
        var first = new InetSocketAddress("192.0.2.10", 6881);
        var second = new InetSocketAddress("192.0.2.11", 6881);
        var now = new AtomicLong();
        var health = new PeerHealthTracker(List.of(first, second), now::get, 8_000);
        health.failure(first);
        health.failure(second);
        health.failure(second);

        var result = health.scanLease(Set.of(0),
                new PeerPieceAvailability(1, Map.of()), Set.of(first, second));

        assertNull(result.peer());
        assertEquals(Duration.ofMillis(250).toNanos(),
                result.nextLeaseDelayNanos());
    }
```

This locks the existing earliest-serving-Peer delay semantics while both Peers are cooling and the failure set prevents a fallback lease.

- [x] **Step 3: Run the focused test and verify the expected RED state**

From `backend`, run:

```powershell
mvn -q "-Dtest=PeerHealthTrackerTest" test
```

Expected before production changes: Exit Code `1` because `scanLease(...)` is not yet defined. This is the intended missing-behavior failure, not a test assertion or environment failure.

## Task 2: Implement the combined scan and connect the wait path

**Files:**
- Modify: `backend/src/main/java/cc/ivera/magnet/engine/piece/PeerHealthTracker.java`

- [x] **Step 1: Add the package-private result and wrapper**

Place the result beside `LeaseWaitResult`:

```java
    record LeaseScanResult(InetSocketAddress peer,
                           long nextLeaseDelayNanos) {}

    synchronized LeaseScanResult scanLease(
            Set<Integer> pieces,
            PeerPieceAvailability availability,
            Set<InetSocketAddress> failedThisRound) {
        if(failedThisRound==null)
            throw new IllegalArgumentException("Missing failed Peer set");
        return scanLease(pieces,Set.of(),availability,failedThisRound,true);
    }
```

The method remains package-private and does not add an external API. A selected Peer increments `inFlight` exactly once inside the combined scan.

- [x] **Step 2: Move the existing private selection logic behind a result-returning helper**

Change the private helper used by the existing non-waiting overloads so it returns only `scanLease(..., false).peer()`. Add a private result helper with the same selection inputs and a `boolean includeDelay` flag. For `includeDelay == false`, preserve the current order: `tried`, excluded/fallback, cooldown, `inFlight`, availability state, ranking, and selection.

For `includeDelay == true`, use this loop structure:

```java
        if(pieces==null || pieces.isEmpty())
            return new LeaseScanResult(null,0);
        int representative=pieces.iterator().next();
        long now=clock.getAsLong();
        long earliest=Long.MAX_VALUE;
        long best=Long.MIN_VALUE,fallbackBest=Long.MIN_VALUE;
        InetSocketAddress chosen=null,fallback=null;
        for(int i=0;i<peers.size();i++) {
            var peer=peers.get(i);
            boolean excludedPeer=excluded.contains(peer);
            var h=records.get(peer);
            if(includeDelay) {
                if(h.inFlight>0)continue;
            } else {
                if(tried.contains(peer) ||
                        (excludedPeer && !allowExcludedFallback) ||
                        now<h.cooldownUntil || h.inFlight>0)continue;
            }

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
            if(includeDelay)
                earliest=Math.min(earliest,Math.max(0,h.cooldownUntil-now));
            if(includeDelay &&
                    (tried.contains(peer) ||
                     (excludedPeer && !allowExcludedFallback) ||
                     now<h.cooldownUntil))continue;

            long rank=performanceScore(h)-
                    Math.floorMod(i-representative,peers.size());
            if(peer.equals(preferredByThread.get(Thread.currentThread())))
                rank+=8_000_000L;
            if(confirmed)rank+=20_000_000L;
            if(excludedPeer) {
                if(fallback==null || rank>fallbackBest) {
                    fallback=peer;fallbackBest=rank;
                }
            } else if(chosen==null || rank>best) {
                chosen=peer;best=rank;
            }
        }
        var selected=chosen!=null?chosen:fallback;
        if(selected!=null)records.get(selected).inFlight++;
        return new LeaseScanResult(selected,earliest);
```

The exact helper may retain the existing `includeDelay == false` fast path rather than literally duplicating this branch, but it must preserve the current non-waiting filtering order. In the combined path the availability state must be read before `tried`, failure fallback, and cooldown selection filters so a cooling or failed idle Peer can still define the wait boundary, matching `nextLeaseDelayNanos()`.

- [x] **Step 3: Replace only the wait loop’s two calls**

In `awaitLeaseWithStatus()`, replace:

```java
            var chosen=leaseExclusive(pieces,availability,failedThisRound);
            if(chosen!=null)return new LeaseWaitResult(chosen,missedInitialLease);
```

and the later `nextLeaseDelayNanos(...)` call with:

```java
            var scan=scanLease(pieces,availability,failedThisRound);
            if(scan.peer()!=null)
                return new LeaseWaitResult(scan.peer(),missedInitialLease);
```

Then pass `scan.nextLeaseDelayNanos()` to `boundedLeaseWaitNanos`. Keep deadline calculation, `missedInitialLease`, 1 ms floor, monitor wait, interruption, and return behavior unchanged. The wait path must perform one combined scan per loop and must not call `leaseExclusive()` or `nextLeaseDelayNanos()` separately.

- [x] **Step 4: Run the focused test and verify GREEN**

Run:

```powershell
mvn -q "-Dtest=PeerHealthTrackerTest" test
```

Expected: Exit Code `0`, including the two new combined-scan tests and all existing health, fallback, cooldown, and wakeup tests.

## Task 3: Run regression, repository, and runtime checks

**Files:**
- No additional source files.

- [x] **Step 1: Run related Native tests**

```powershell
mvn -q "-Dtest=PeerHealthTrackerTest,PeerBlockMailboxTest,PeerRoundConnectionPoolTest,PeerIncrementalFrameReaderTest,NativeWorkerJobManagerTest" test
```

Expected: Exit Code `0`, with no failures or errors.

- [x] **Step 2: Run serial clean tests, compile, and final tests**

```powershell
mvn -q clean test
mvn -q clean compile -DskipTests
mvn -q test
```

Run from `backend` serially. Record actual Surefire totals and exit codes; do not substitute planned counts for command output.

- [x] **Step 3: Check diff and encoding**

From the repository root, run:

```powershell
git diff --check
```

Scan tracked and untracked text files for `EF BB BF`, `FF FE`, and `FE FF`. Expected: diff check Exit Code `0` except any already documented line-ending warning, and zero BOM files.

- [x] **Step 4: Restart the backend and verify one listener**

After Java changes, restart the existing backend with `SERVER_PORT=8082`, wait for successful startup, and run:

```powershell
Get-NetTCPConnection -LocalPort 8082 -State Listen
```

Expected: exactly one listening process owns port 8082. Do not restore, create, or modify any user Native magnet task.

## Task 4: Update handoff with actual evidence

**Files:**
- Replace: `HANDOFF.md`

- [x] **Step 1: Record the finished round**

Replace the handoff with the actual changed files, RED/GREEN evidence, all regression/build results, diff/BOM results, backend PID/log/listener status, and explicit statement that no user magnet task was touched.

- [x] **Step 2: Preserve the measurement boundary**

State that the implementation removes a duplicate lock-held Peer/Piece availability traversal and preserves lease semantics. Do not report a download-speed or throughput percentage without a comparable unfinished Native task and paired diagnostic samples.

## Self-review

- **Spec coverage:** Tasks cover the package-private combined result, one availability state traversal per idle Peer, one clock read per combined scan, delay calculation before selection filters, preserved fallback/scoring/`inFlight` behavior, wait-path integration, focused RED/GREEN, full verification, listener restart, and the no-speed-claim boundary.
- **Placeholder scan:** No unresolved placeholder instructions remain in this plan.
- **Type consistency:** `LeaseScanResult`, `scanLease(...)`, and `nextLeaseDelayNanos()` have consistent names and types in the tests, helper, and wait loop; existing `LeaseWaitResult` remains unchanged.
