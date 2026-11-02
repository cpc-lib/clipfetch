# Native Peer Lease Single-Pass Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the duplicate Peer-candidate scan performed inside the Native health tracker monitor while preserving the existing lease selection and failed-Peer fallback result.

**Architecture:** Keep the existing synchronized lease entry points and scoring code in `PeerHealthTracker`. Replace the two-phase selector used by the failed-Peer overload with one Peer pass that records the best unfailed candidate and the best failed fallback candidate, then increments `inFlight` only for the final selection. Add one deterministic regression test that counts Piece-set iteration during the fallback case, so the old two-scan implementation fails before the production change.

**Tech Stack:** Java 21, Spring Boot Native BitTorrent engine, JUnit 5, Maven, PowerShell.

---

### Task 1: Add the single-pass regression test

**Files:**
- Modify: `backend/src/test/java/cc/ivera/magnet/engine/piece/PeerHealthTrackerTest.java`

- [x] **Step 1: Add the failing test**

Add the imports `java.util.AbstractSet`, `java.util.Iterator`, and `java.util.concurrent.atomic.AtomicInteger`, then add this test after the existing failed-Peer fallback test:

```java
    @Test
    void failedPeerFallbackScansEachPeerOnce() {
        var healthy = new InetSocketAddress("192.0.2.10", 6881);
        var failed = new InetSocketAddress("192.0.2.11", 6881);
        var now = new AtomicLong();
        var availability = new PeerPieceAvailability(1, Map.of(healthy, new java.util.BitSet()));
        var health = new PeerHealthTracker(List.of(healthy, failed), now::get, 8_000);
        health.failure(failed);
        now.set(Duration.ofMillis(250).toNanos());

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

        assertEquals(failed, health.leaseExclusive(pieces, availability, Set.of(failed)));
        assertEquals(4, pieceIterations.get());
        health.release(failed);
    }
```

The expected count is one iterator for the representative Piece, one for the healthy Peer availability check, and two for the failed fallback Peer availability and confirmation checks. The old implementation re-enters the selector for its fallback scan, re-reads the representative Piece, and produces `6`.

- [x] **Step 2: Run the focused test and verify RED**

Run from `backend`:

```powershell
mvn -q "-Dtest=PeerHealthTrackerTest" test
```

Expected: the test run fails at `failedPeerFallbackScansEachPeerOnce` with an assertion showing expected `4` and actual `6`; the failure must be an assertion failure rather than a compilation error.

### Task 2: Implement one-pass candidate selection

**Files:**
- Modify: `backend/src/main/java/cc/ivera/magnet/engine/piece/PeerHealthTracker.java:338-410`

- [x] **Step 1: Route both collection-based lease overloads through one selector**

Replace the current two-call fallback in the failed-Peer overload and add a boolean to the private selector so the existing no-failed overload keeps its current behavior:

```java
    synchronized InetSocketAddress leaseExclusive(Set<Integer> pieces,
                                                  Set<InetSocketAddress> tried,
                                                  PeerPieceAvailability availability) {
        return leaseExclusive(pieces,tried,availability,Set.of(),false);
    }
    synchronized InetSocketAddress leaseExclusive(Set<Integer> pieces,
                                                  PeerPieceAvailability availability,
                                                  Set<InetSocketAddress> failedThisRound) {
        if(failedThisRound==null)
            throw new IllegalArgumentException("Missing failed Peer set");
        return leaseExclusive(pieces,Set.of(),availability,failedThisRound,true);
    }
```

Change the private selector signature to accept `boolean allowExcludedFallback`, then replace its candidate loop and final increment with this single-pass implementation. Keep the existing `tried`, cooldown, `inFlight`, availability, score, thread-affinity, confirmed-piece, and tie-break expressions unchanged:

```java
    private InetSocketAddress leaseExclusive(Set<Integer> pieces,
                                             Set<InetSocketAddress> tried,
                                             PeerPieceAvailability availability,
                                             Set<InetSocketAddress> excluded,
                                             boolean allowExcludedFallback) {
        if(pieces==null || pieces.isEmpty())return null;
        int representative=pieces.iterator().next();
        long now=clock.getAsLong();
        long best=Long.MIN_VALUE,fallbackBest=Long.MIN_VALUE;
        InetSocketAddress chosen=null,fallback=null;
        for(int i=0;i<peers.size();i++) {
            var peer=peers.get(i);
            boolean excludedPeer=excluded.contains(peer);
            if(tried.contains(peer) || (excludedPeer && !allowExcludedFallback))continue;
            var h=records.get(peer);
            if(now<h.cooldownUntil || h.inFlight>0)continue;
            boolean canServe=false;
            for(int piece:pieces) {
                if(availability==null || availability.couldHave(peer,piece)) {
                    canServe=true;break;
                }
            }
            if(!canServe)continue;
            long rank=performanceScore(h)-Math.floorMod(i-representative,peers.size());
            if(peer.equals(preferredByThread.get(Thread.currentThread())))
                rank+=8_000_000L;
            if(availability!=null) {
                for(int piece:pieces)
                    if(availability.confirmed(peer,piece)) {rank+=20_000_000L;break;}
            }
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
        return selected;
    }
```

The single pass still prefers any eligible unfailed Peer over every failed fallback Peer. It only uses a failed candidate when no unfailed candidate was eligible, and it increments the selected record once.

- [x] **Step 2: Run the focused tests and verify GREEN**

Run:

```powershell
mvn -q "-Dtest=PeerHealthTrackerTest" test
```

Expected: Exit Code `0`; the new scan-count test and all existing health, cooldown, fallback, compatibility, and wakeup tests pass.

### Task 3: Run related and repository verification

**Files:**
- Modify: `docs/superpowers/plans/2026-10-21-native-peer-lease-single-pass.md`
- Replace: `HANDOFF.md`

- [x] **Step 1: Run the related Native tests**

Run from `backend`:

```powershell
mvn -q "-Dtest=PeerBlockMailboxTest,PeerHealthTrackerTest,PeerRoundConnectionPoolTest" test
```

Expected: Exit Code `0` with no test failures or errors.

- [x] **Step 2: Run the full backend tests and clean compile**

Run separately from `backend`:

```powershell
mvn -q clean test
mvn -q clean compile -DskipTests
```

Expected: both commands exit `0`. Record the actual test count and exit codes rather than claiming a throughput change.

- [x] **Step 3: Check the worktree diff and text encoding**

Run from the repository root:

```powershell
git diff --check
$trackedFiles = @(git diff --name-only)
$untrackedFiles = @(git ls-files --others --exclude-standard)
$textFiles = @($trackedFiles + $untrackedFiles) | Where-Object { $_ -match '\.(java|js|vue|xml|yml|yaml|sql|md|ps1|conf|properties)$' } | Sort-Object -Unique
$bomFiles = @(
    foreach($file in $textFiles) {
        if(Test-Path $file) {
            $bytes=[System.IO.File]::ReadAllBytes((Join-Path (Get-Location) $file))
            if(($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF) -or
               ($bytes.Length -ge 2 -and (($bytes[0] -eq 0xFF -and $bytes[1] -eq 0xFE) -or ($bytes[0] -eq 0xFE -and $bytes[1] -eq 0xFF)))) { $file }
        }
    }
)
if($bomFiles.Count -gt 0) { throw "BOM found: $($bomFiles -join ', ')" }
Write-Output "BOM scan passed for $($textFiles.Count) changed text files"
```

Expected: `git diff --check` exits `0` apart from any already-recorded line-ending warning, and the BOM scan prints that it passed with no file names.

- [x] **Step 4: Restart and verify the backend listener**

Stop only the current Maven-run backend after recording its Java/starter PIDs, then start from `backend`:

```powershell
$env:SERVER_PORT='8082'; mvn spring-boot:run
```

Verify in another PowerShell session:

```powershell
Get-NetTCPConnection -LocalPort 8082 -State Listen | Select-Object LocalAddress,LocalPort,OwningProcess
```

Expected: exactly one listener is present on port `8082`, the startup log reaches Spring Boot/Tomcat readiness, and no Native task is automatically resumed or created.

- [x] **Step 5: Replace the handoff with actual evidence**

Fully replace `HANDOFF.md` with this round’s real changed files, RED/GREEN and repository command exit codes, listener PID, and remaining measurement boundary. State that A is implemented and verified, and that B still requires a separate design before code changes. Do not create a commit.

## Scope boundaries

- No changes to Peer count, Actor exploration slots, Block request windows, timeout values, cooldown durations, `awaitLease`, network protocol, persistence, APIs, or frontend code.
- No new configuration key, cache, queue, thread, timer, database migration, or benchmark claim.
- Preserve all existing worktree changes from earlier Native optimization rounds.

## Plan self-review

- Spec coverage: the one-pass selector, preferred-candidate priority, controlled failed-Peer fallback, single `inFlight` increment, compatible overload, focused regression coverage, full verification, and runtime restart are all covered above.
- Placeholder scan: no step relies on an unresolved marker, an unspecified helper, or a future design decision; all edited paths, method signatures, test code, and commands are concrete.
- Type consistency: the test uses existing `PeerHealthTracker.leaseExclusive(Set<Integer>, PeerPieceAvailability, Set<InetSocketAddress>)`; the implementation routes both collection-based overloads to the five-argument private selector; no other caller signature changes.
