# Native Round Peer Quarantine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prevent a Native multiplexed round from repeatedly preferring Peers that already failed in that same round, while allowing controlled fallback when no alternative Peer exists.

**Architecture:** Keep the existing cross-round `PeerHealthTracker` scores, cooldowns, socket lifecycle, and Actor limits unchanged. Add a round-local failed-peer exclusion to the Actor-only lease path; first choose candidates outside the failed set, then fall back to the existing candidate set only when no non-failed candidate can currently be leased. Add one diagnostic counter for those fallbacks.

**Tech Stack:** Java 21, JUnit 5, Spring Boot Native BitTorrent engine, Maven, existing `PeerHealthTracker` and multiplexed mailbox.

---

### Task 1: Add failing lease-isolation tests

**Files:**
- Modify: `backend/src/test/java/cc/ivera/magnet/engine/piece/PeerHealthTrackerTest.java`

- [x] **Step 1: Add the failing tests**

Add `Map`, `Set`, `AtomicLong`, and `assertNull` imports, then add these package-local tests:

```java
@Test
void failedPeerIsSkippedWhenAlternativeExists() {
    var failed=new InetSocketAddress("192.0.2.10",6881);
    var healthy=new InetSocketAddress("192.0.2.11",6881);
    var availability=new PeerPieceAvailability(1,Map.of());
    var health=new PeerHealthTracker(List.of(failed,healthy),() -> 0L,8_000);
    health.failure(failed);

    var leased=health.leaseExclusive(Set.of(0),availability,Set.of(failed));

    assertEquals(healthy,leased);
    health.release(leased);
}

@Test
void failedPeerIsUsedOnlyAsFallbackAfterCooldownWhenNoAlternativeExists() {
    var failed=new InetSocketAddress("192.0.2.10",6881);
    var now=new AtomicLong();
    var availability=new PeerPieceAvailability(1,Map.of());
    var health=new PeerHealthTracker(List.of(failed),now::get,8_000);
    health.failure(failed);

    assertNull(health.leaseExclusive(Set.of(0),availability,Set.of(failed)));
    now.set(Duration.ofMillis(250).toNanos());
    assertEquals(failed,health.leaseExclusive(Set.of(0),availability,Set.of(failed)));
    health.release(failed);
}

@Test
void existingExclusiveLeaseOverloadStillSelectsAvailablePeer() {
    var peer=new InetSocketAddress("192.0.2.10",6881);
    var availability=new PeerPieceAvailability(1,Map.of());
    var health=new PeerHealthTracker(List.of(peer),() -> 0L,8_000);

    assertEquals(peer,health.leaseExclusive(Set.of(0),availability));
    health.release(peer);
}
```

- [x] **Step 2: Run the focused test and confirm RED**

Run from `backend`:

```powershell
mvn -q "-Dtest=PeerHealthTrackerTest" test
```

Expected: compilation failure because the new three-argument lease method does not exist yet. The failure must reference the test calls, not a malformed test import.

### Task 2: Implement round-local failed-peer isolation

**Files:**
- Modify: `backend/src/main/java/cc/ivera/magnet/engine/piece/PeerHealthTracker.java`
- Modify: `backend/src/main/java/cc/ivera/magnet/engine/piece/PeerMultiplexedRound.java`

- [x] **Step 1: Add the filtered lease path**

Preserve the existing three-argument `leaseExclusive(pieces,tried,availability)` behavior by routing it through a private selector with an empty exclusion set. Add the Actor-specific overload:

```java
synchronized InetSocketAddress leaseExclusive(Set<Integer> pieces,
                                               PeerPieceAvailability availability,
                                               Set<InetSocketAddress> failedThisRound) {
    if(failedThisRound==null)
        throw new IllegalArgumentException("Missing failed Peer set");
    var chosen=leaseExclusive(pieces,Set.of(),availability,failedThisRound);
    if(chosen!=null)return chosen;
    return leaseExclusive(pieces,Set.of(),availability,Set.of());
}
```

The private selector must keep all existing checks and ranking rules, adding only:

```java
if(tried.contains(peer) || excluded.contains(peer))continue;
```

The fallback must still honor `cooldownUntil`, `inFlight`, `availability`, and the current performance score. Do not clear or mutate `failedThisRound` inside the health tracker.

- [x] **Step 2: Pass the failure set from the multiplexed Actor supervisor**

In `PeerMultiplexedRound.fetch`, add a round-local counter beside `noWorkWaits`:

```java
var failedPeerFallbacks=new java.util.concurrent.atomic.AtomicInteger();
```

Change the Actor lease call to:

```java
InetSocketAddress endpoint=health.leaseExclusive(
        remaining,availability,failedThisRound);
if(endpoint!=null && failedThisRound.contains(endpoint))
    failedPeerFallbacks.incrementAndGet();
```

Keep the existing `health.failure(endpoint)` and `failedThisRound.add(endpoint)` behavior unchanged. Extend `logFailureDiagnostics` with the counter and append `failedPeerFallbacks={}` to the existing log message. Do not change user-visible APIs or the meaning of existing metrics.

- [x] **Step 3: Run the focused tests and confirm GREEN**

Run:

```powershell
mvn -q "-Dtest=PeerHealthTrackerTest,PeerRoundConnectionPoolTest" test
```

Expected: all focused tests pass with no compilation errors.

### Task 3: Verify the full backend and live process

**Files:**
- Modify: `docs/superpowers/plans/2026-10-21-native-round-peer-quarantine.md`
- Replace: `HANDOFF.md`

- [x] **Step 1: Run the full verification commands**

Run separately from `backend`:

```powershell
mvn -q clean test
mvn -q clean compile -DskipTests
```

Then from the repository root run:

```powershell
git diff --check
```

Expected: every command exits `0`. Also scan all changed text files for UTF-8/UTF-16 BOM and record the result in `HANDOFF.md`.

- [x] **Step 2: Restart and verify the backend**

Stop the current Maven-run backend, start it from `backend` with:

```powershell
$env:SERVER_PORT='8082'; mvn spring-boot:run
```

Verify `Get-NetTCPConnection -LocalPort 8082 -State Listen` shows the new Java PID listening on `8082`. Do not print `.env` secrets.

- [x] **Step 3: Update handoff with actual evidence**

Replace `HANDOFF.md` with this round's actual changed files, the intentional RED result, focused GREEN and full verification exit codes, the new listener PID, the new `failedPeerFallbacks` diagnostic field, and the boundary that no speedup is claimed without a comparable unfinished-task benchmark. Do not create a commit.
