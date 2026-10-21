# Native Adaptive Peer Timeout Implementation Plan

> **For agentic workers:** Execute this plan inline in the current workspace. Do not create a commit unless the user explicitly requests one.

**Goal:** Reduce Native BitTorrent time lost on silent or slow Peer sockets while preserving enough wait time for Peers with proven high latency, and make the multiplexed received-block metric truthful.

**Architecture:** Add one environment-backed first-block timeout. The multiplexed connection pool chooses the no-progress deadline from that timeout for Peers without RTT samples, and from four times the Peer RTT P95 for known Peers, bounded by the configured global stall timeout. The existing socket-close, mailbox release, failure cooldown, and reassignment paths remain unchanged. Count accepted mailbox winners as received Blocks instead of reporting mailbox timeouts as transferred Blocks.

**Tech Stack:** Java 21, Spring Boot configuration properties, JUnit 5, Maven, existing Native BitTorrent Peer mailbox.

---

### Task 1: Lock down timeout policy with a failing test

**Files:**
- Create: `backend/src/test/java/cc/ivera/magnet/engine/piece/PeerRoundConnectionPoolTest.java`

- [x] **Step 1: Write the failing test**

  Add package-local JUnit tests for the desired pure policy method:

  ```java
  package cc.ivera.magnet.engine.piece;

  import org.junit.jupiter.api.Test;

  import java.time.Duration;

  import static org.junit.jupiter.api.Assertions.assertEquals;
  import static org.junit.jupiter.api.Assertions.assertThrows;

  class PeerRoundConnectionPoolTest {
      @Test
      void unknownPeerUsesConfiguredFirstBlockTimeout() {
          assertEquals(Duration.ofSeconds(12).toNanos(),
                  PeerRoundConnectionPool.adaptiveNoProgressTimeoutNanos(
                          12_000,36_000,0));
      }

      @Test
      void knownPeerUsesRttP95WithFloorAndGlobalCap() {
          assertEquals(Duration.ofSeconds(12).toNanos(),
                  PeerRoundConnectionPool.adaptiveNoProgressTimeoutNanos(
                          12_000,36_000,Duration.ofSeconds(2).toNanos()));
          assertEquals(Duration.ofSeconds(32).toNanos(),
                  PeerRoundConnectionPool.adaptiveNoProgressTimeoutNanos(
                          12_000,36_000,Duration.ofSeconds(8).toNanos()));
          assertEquals(Duration.ofSeconds(36).toNanos(),
                  PeerRoundConnectionPool.adaptiveNoProgressTimeoutNanos(
                          12_000,36_000,Duration.ofSeconds(20).toNanos()));
      }

      @Test
      void firstBlockTimeoutCannotExceedGlobalStallTimeout() {
          assertThrows(IllegalArgumentException.class,
                  () -> PeerRoundConnectionPool.adaptiveNoProgressTimeoutNanos(
                          36_001,36_000,0));
      }
  }
  ```

- [x] **Step 2: Run test to verify it fails**

  Run from `backend`:

  ```powershell
  mvn -q -Dtest=PeerRoundConnectionPoolTest test
  ```

  Expected: compilation failure because `adaptiveNoProgressTimeoutNanos` does not exist yet.

### Task 2: Implement the adaptive timeout and wire configuration

**Files:**
- Modify: `.env.example`
- Modify: `backend/.env`
- Modify: `backend/src/main/resources/application.yml`
- Modify: `backend/src/main/java/cc/ivera/magnet/engine/config/NativeTorrentProperties.java`
- Modify: `backend/src/main/java/cc/ivera/magnet/engine/config/NativeTorrentConfiguration.java`
- Modify: `backend/src/main/java/cc/ivera/magnet/engine/piece/PeerPieceDownloadSession.java`
- Modify: `backend/src/main/java/cc/ivera/magnet/engine/piece/PeerRoundConnectionPool.java`

- [x] **Step 1: Add the environment-backed setting**

  Add `TORRENT_NATIVE_PEER_FIRST_BLOCK_TIMEOUT_MILLIS=12000` to `.env.example` and `backend/.env`, add `peer-first-block-timeout-millis` to `application.yml`, expose the matching property getter/setter, and pass it through the existing configuration/session/pool constructor chain. Keep existing constructors as delegating compatibility overloads.

- [x] **Step 2: Implement the minimal policy**

  Add a package-local static method that validates the first-block timeout is at least 1 second and no greater than the effective global stall timeout. For an unknown Peer return the first-block timeout. For a known Peer return `min(globalStall, max(firstBlock, p95 * 4))`, with saturating multiplication.

- [x] **Step 3: Apply the policy to multiplexed mailbox reads**

  Compute the no-progress deadline after the socket is established and refresh it after every accepted Block so the first response can promote a known Peer to its RTT-derived deadline. Replace only the fixed `stallTimeoutMillis` comparison; keep the existing `SocketTimeoutException`, socket retirement, mailbox disconnect, cancellation, and failure-cooldown behavior.

- [x] **Step 4: Run the focused tests**

  Run:

  ```powershell
  mvn -q -Dtest=PeerRoundConnectionPoolTest test
  ```

  Expected: all three tests pass.

### Task 3: Correct the multiplexed received-block metric

**Files:**
- Modify: `backend/src/main/java/cc/ivera/magnet/engine/piece/PeerMultiplexedRound.java`
- Modify: `backend/src/main/java/cc/ivera/magnet/engine/piece/PieceDownloadCoordinator.java`

- [x] **Step 1: Count accepted wire Blocks**

  Increment an `AtomicLong` inside the mailbox winner callback, after a Block has been accepted by the mailbox and before the callback processes the Piece assembler. Return that count from `Download` as `receivedBlocks`; keep stale-response count separate.

- [x] **Step 2: Rename the log fields to their real meaning**

  Change the coordinator log from `transferredBlocks` to `receivedBlocks` and use the renamed record accessors. Do not change persistence or user-visible APIs.

- [x] **Step 3: Compile and run the full backend test suite**

  Run from `backend`:

  ```powershell
  mvn -q clean test
  mvn -q clean compile -DskipTests
  ```

  Expected: both commands exit 0.

### Task 4: Restart and verify the live Native process

**Files:**
- Replace: `HANDOFF.md`

- [x] **Step 1: Restart the backend**

  Stop the current Maven run, start it again with `SERVER_PORT=8082`, and verify the new PID is listening on 8082. A restart is required because configuration and controller/application classes are loaded by the JVM.

- [x] **Step 2: Check the configured runtime values**

  Confirm from startup/configuration evidence that the Native engine receives the first-block timeout of 12 seconds and the existing 36-second global stall cap. Do not print secrets or the full `.env`.

- [ ] **Step 3: Update the handoff**

  Replace `HANDOFF.md` with the actual changed files, exact verification commands and exit codes, live-process status, remaining measurement boundary, and the next step for a controlled Native download benchmark. Do not claim a speedup unless the new download run supplies comparable before/after timing.
