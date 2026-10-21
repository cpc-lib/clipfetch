# Native Actor Admission Failure Filter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans (recommended) to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prevent a verified Peer that failed once in the previous Native round from contributing to the next round's Actor admission target while preserving exploration and retry behavior.

**Architecture:** Change only `PeerHealthTracker.healthyVerifiedPeerCount()` so a Peer contributes historical verified capacity when it has at least one verified success and zero consecutive failures. Keep the existing failure cooldown, lease selection, round-local quarantine, exploration slots, and successful recovery path unchanged.

**Tech Stack:** Java 21, JUnit 5, Spring Boot Native BitTorrent engine, Maven.

---

### Task 1: Add the admission regression test

**Files:**
- Modify: `backend/src/test/java/cc/ivera/magnet/engine/piece/PeerHealthTrackerTest.java`

- [x] **Step 1: Add the failing test**

Append this package-local test after the existing degraded-peer test:

```java
@Test
void oneFailedVerifiedPeerDoesNotIncreaseActorTarget() {
    var healthy = new InetSocketAddress("192.0.2.10", 6881);
    var failed = new InetSocketAddress("192.0.2.11", 6881);
    var other = new InetSocketAddress("192.0.2.12", 6881);
    var health = new PeerHealthTracker(List.of(healthy, failed, other), () -> 0L, 8_000);

    health.success(healthy);
    health.success(failed);
    health.success(other);
    health.failure(failed);

    assertEquals(6, PeerMultiplexedRound.computeTargetActors(health, 4, 64));
}
```

- [x] **Step 2: Run the focused test and confirm RED**

Run from `backend`:

```powershell
mvn -q "-Dtest=PeerHealthTrackerTest" test
```

Expected: the new test fails with an assertion showing `expected: 6` and `actual: 7`, because the current implementation still accepts `consecutiveFailures < 2`.

### Task 2: Tighten only the healthy admission predicate

**Files:**
- Modify: `backend/src/main/java/cc/ivera/magnet/engine/piece/PeerHealthTracker.java:92-96`

- [x] **Step 1: Change the predicate**

Replace the existing condition:

```java
if(h.successes>0 && h.consecutiveFailures<2)count++;
```

with:

```java
if(h.successes>0 && h.consecutiveFailures==0)count++;
```

Do not change `failure()`, `success()`, `degradedCount()`, lease selection, cooldowns, exploration slots, or the round-local failed-peer set.

- [x] **Step 2: Run focused GREEN verification**

Run:

```powershell
mvn -q "-Dtest=PeerHealthTrackerTest,PeerRoundConnectionPoolTest" test
```

Expected: Exit Code `0`; the new single-failure test and the existing two-failure test both pass.

### Task 3: Verify and hand off the live backend

**Files:**
- Modify: `docs/superpowers/plans/2026-10-21-native-admission-failure-filter.md`
- Replace: `HANDOFF.md`

- [x] **Step 1: Run full verification**

Run separately from `backend`:

```powershell
mvn -q clean test
mvn -q clean compile -DskipTests
```

Then from the repository root run:

```powershell
git diff --check
```

Also scan every changed text file for UTF-8/UTF-16 BOM and record the exact result in `HANDOFF.md`.

- [x] **Step 2: Restart and verify port 8082**

Stop the current Maven-run backend and start:

```powershell
$env:SERVER_PORT='8082'; mvn spring-boot:run
```

Verify `Get-NetTCPConnection -LocalPort 8082 -State Listen` reports the new Java PID.

- [x] **Step 3: Replace HANDOFF with actual evidence**

Record this round's hypothesis, files, RED/GREEN/full verification exit codes, listener PID, and the boundary that no real speedup is claimed without a comparable unfinished-task run. Do not create a commit.
