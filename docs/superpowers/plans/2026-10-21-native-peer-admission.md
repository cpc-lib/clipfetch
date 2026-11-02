# Native Peer Admission Implementation Plan

> **For agentic workers:** Execute this plan inline in the current workspace. Do not create a commit unless the user explicitly requests one.

**Goal:** Prevent partial Block transfers and degraded Peers from inflating Native multiplexed Actor admission.

**Architecture:** Move session success promotion behind a `PeerHealthTracker.observeSession` contract that requires at least one SHA-1-verified Piece. Count healthy verified Peers separately from all historical successes, and use that count in the existing `verified + exploration` Actor target formula. Keep socket, timeout, retry, discovery, persistence, and configured hard limits unchanged.

**Tech Stack:** Java 21, JUnit 5, Spring Boot Native BitTorrent engine, Maven.

---

### Task 1: Add failing Peer admission tests

**Files:**
- Create: `backend/src/test/java/cc/ivera/magnet/engine/piece/PeerHealthTrackerTest.java`

- [x] **Step 1: Write the failing tests**

  Cover these behaviors:

  ```java
  @Test
  void partialSessionDoesNotPromotePeer() {
      var peer = new InetSocketAddress("192.0.2.10", 6881);
      var health = new PeerHealthTracker(List.of(peer), () -> 0L, 8_000);
      health.observeSession(peer, 16 * 1024, Duration.ofSeconds(1).toNanos(), 0);
      assertEquals(0, health.verifiedPeerCount());
      health.observeSession(peer, 16 * 1024, Duration.ofSeconds(1).toNanos(), 1);
      assertEquals(1, health.verifiedPeerCount());
  }

  @Test
  void degradedVerifiedPeerDoesNotIncreaseActorTarget() {
      var healthy = new InetSocketAddress("192.0.2.10", 6881);
      var degraded = new InetSocketAddress("192.0.2.11", 6881);
      var health = new PeerHealthTracker(List.of(healthy, degraded), () -> 0L, 8_000);
      health.success(healthy);
      health.success(degraded);
      health.failure(degraded);
      health.failure(degraded);
      assertEquals(5, PeerMultiplexedRound.computeTargetActors(health, 4, 64));
  }
  ```

- [x] **Step 2: Run the focused test and confirm RED**

  Run from `backend`:

  ```powershell
  mvn -q -Dtest=PeerHealthTrackerTest test
  ```

  Expected: compilation failure because `observeSession` does not exist; the target assertion would also fail before healthy-peer admission is implemented.

### Task 2: Implement verified-session promotion and healthy admission

**Files:**
- Modify: `backend/src/main/java/cc/ivera/magnet/engine/piece/PeerHealthTracker.java`
- Modify: `backend/src/main/java/cc/ivera/magnet/engine/piece/PeerMultiplexedRound.java`

- [x] **Step 1: Add the health tracker contract**

  Add `observeSession(peer, bytes, elapsedNanos, verifiedPieces)`. Return without changing health when `verifiedPieces < 1`; otherwise delegate to the existing throughput-aware `success` method when bytes and elapsed time are positive.

- [x] **Step 2: Count verified Pieces per multiplexed Actor session**

  Increment a session-local counter only when `assembler.verifiedPiece(info)` succeeds and `ready.putIfAbsent` accepts the Piece. Replace the unconditional `health.success` call after `runMailbox` with `health.observeSession` using that counter. Keep the existing failure and release paths unchanged.

- [x] **Step 3: Use healthy verified Peers for Actor targets**

  Add a synchronized healthy-verified count that includes successful Peers unless they match the existing degraded condition. Change `computeTargetActors` to use this count, retaining the existing exploration slot and `maxActors` bounds.

- [x] **Step 4: Run the focused tests and confirm GREEN**

  Run:

  ```powershell
  mvn -q -Dtest=PeerHealthTrackerTest,PeerRoundConnectionPoolTest test
  ```

  Expected: all focused tests pass.

### Task 3: Verify the backend and live process

**Files:**
- Replace: `HANDOFF.md`

- [x] **Step 1: Run full verification**

  ```powershell
  mvn -q clean test
  mvn -q clean compile -DskipTests
  git diff --check
  ```

  Expected: all commands exit 0.

- [x] **Step 2: Restart the backend**

  Stop the current Maven-run JVM, start `SERVER_PORT=8082; mvn spring-boot:run` from `backend`, and verify a new Java process listens on 8082.

- [x] **Step 3: Update the handoff with evidence**

  Replace `HANDOFF.md` with the actual changed files, red/green test results, full verification exit codes, new PID/listener state, and the explicit boundary that no real speedup is claimed without a comparable unfinished-task benchmark.
