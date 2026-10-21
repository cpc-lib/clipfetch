# Native Exploration Window Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the Native multiplexed unknown-Peer exploration window configurable and reduce its active default from 64 to 32 Actors.

**Architecture:** Keep Actor admission in `PeerMultiplexedRound.computeTargetActors`. The Spring-bound Native properties carry the exploration value into the task worker, and the existing coordinator receives it through a narrow configuration method before a round starts. No transport or persistence behavior changes.

**Tech Stack:** Java 21, Spring Boot configuration properties, JUnit 5, Maven, PowerShell.

---

### Task 1: Add the failing configuration contract test

**Files:**
- Modify: `backend/src/test/java/cc/ivera/magnet/engine/piece/PeerHealthTrackerTest.java`

- [x] **Step 1: Add focused configuration assertions**

Add these tests to the existing focused test class. The production property and its accessor do not exist yet, so this deliberately defines the missing contract first.

```java
@Test
void nativePropertiesDefaultToThirtyTwoExplorationSlots() {
    assertEquals(32,
            new cc.ivera.magnet.engine.config.NativeTorrentProperties()
                    .getPeerExplorationSlots());
}

@Test
void nativePropertiesAllowAConfiguredExplorationWindow() {
    var properties = new cc.ivera.magnet.engine.config.NativeTorrentProperties();
    properties.setPeerExplorationSlots(2);

    assertEquals(2, properties.getPeerExplorationSlots());
}
```

- [x] **Step 2: Run the focused test**

Run from `backend`:

```powershell
mvn -q "-Dtest=PeerHealthTrackerTest" test
```

Expected: FAIL at compilation because `getPeerExplorationSlots` and `setPeerExplorationSlots` do not exist yet.

### Task 2: Thread the configuration into the multiplexed coordinator

**Files:**
- Modify: `backend/src/main/java/cc/ivera/magnet/engine/config/NativeTorrentProperties.java`
- Modify: `backend/src/main/resources/application.yml`
- Modify: `.env.example`
- Modify: `backend/.env`
- Modify: `backend/src/main/java/cc/ivera/magnet/engine/piece/PieceDownloadCoordinator.java`
- Modify: `backend/src/main/java/cc/ivera/magnet/engine/piece/NativeTaskWorkerRoundService.java`
- Modify: `backend/src/test/java/cc/ivera/magnet/engine/piece/PeerHealthTrackerTest.java`

- [x] **Step 1: Add the minimum configuration property**

Add `peerExplorationSlots = 32` to `NativeTorrentProperties` with a getter and setter. Bind it in `application.yml` as:

```yaml
peer-exploration-slots: ${TORRENT_NATIVE_PEER_EXPLORATION_SLOTS:32}
```

Add `TORRENT_NATIVE_PEER_EXPLORATION_SLOTS=32` to `.env.example` and `backend/.env` beside the other Native concurrency settings.

- [x] **Step 2: Add the coordinator configuration boundary**

Add a coordinator field initialized to `32` and a package-private method:

```java
void configureMultiplexedExplorationSlots(int slots) {
    if (slots < 1 || slots > MAX_PARALLEL_PIECES)
        throw new IllegalArgumentException("Invalid multiplexed exploration slots");
    multiplexedExplorationSlots = slots;
}
```

Replace the hard-coded `Math.min(64,actorCap)` in `parallelMultiplexed` with `Math.min(multiplexedExplorationSlots,actorCap)`.

- [x] **Step 3: Wire the Native setting**

Immediately after constructing the coordinator in `NativeTaskWorkerRoundService`, call:

```java
coordinator.configureMultiplexedExplorationSlots(settings.getPeerExplorationSlots());
```

- [x] **Step 4: Run focused tests**

Run:

```powershell
mvn -q "-Dtest=PeerHealthTrackerTest" test
```

Expected: PASS, including the new default-property assertion.

### Task 3: Verify the full change and runtime loading

**Files:**
- Modify: `docs/superpowers/specs/2026-10-21-native-exploration-window-design.md`
- Modify: `docs/superpowers/plans/2026-10-21-native-exploration-window.md`
- Modify: `HANDOFF.md`

- [x] **Step 1: Run full backend verification**

From `backend`, run:

```powershell
mvn -q clean test
mvn -q clean compile -DskipTests
```

Expected: both commands exit `0`.

- [x] **Step 2: Check file hygiene**

From the repository root, run `git diff --check` and scan all changed text files for UTF-8/UTF-16 BOM. Expected: no whitespace errors and no BOM.

- [x] **Step 3: Restart and verify the listener**

Restart the backend with `$env:SERVER_PORT='8082'; mvn spring-boot:run`; then verify `Get-NetTCPConnection -LocalPort 8082 -State Listen` reports the new Java process listening on `8082`.

- [x] **Step 4: Update HANDOFF**

Fully replace `HANDOFF.md` with this round's actual goal, files, exact command exit codes, runtime PID, and the explicit boundary that no speed claim was made without an unfinished download benchmark.
