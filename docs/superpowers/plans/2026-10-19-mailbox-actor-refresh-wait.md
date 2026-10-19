# Mailbox Actor Refresh Wait Implementation Plan

**Goal:** Prevent a multiplexed download round from collapsing to one surviving Peer while failed Peers cool down or asynchronous Tracker/DHT/PeX refresh is still running.

**Success criteria:**

1. A mailbox Actor whose current Peer fails remains available for a Peer discovered later in the same bounded round.
2. Waiting is interruptible and ends at the existing round deadline; no scheduler, persistent background loop, config change, or extra memory buffer is introduced.
3. Existing Actor mutual exclusion, cancellation, mailbox, and full backend tests pass.
4. After a safe checkpoint-preserving restart, a bounded public-swarm sample shows that request capacity/active Peer work recovers after degradation and records completed Peer refresh when discovery yields candidates.

## Task 1: Reproduce the Actor-collapse bug

**Files:**
- Modify: `backend/src/test/java/cc/ivera/magnet/engine/piece/PeerMultiplexedActorSupervisorIntegrationTest.java`

1. Add a real-TCP regression where the initial Peer fails and a healthy Peer is merged into the task health tracker shortly afterward.
2. Verify the test fails because the Actor exits immediately when `leaseExclusive(...)` temporarily returns `null`.

## Task 2: Keep the supervisor alive within the existing deadline

**Files:**
- Modify: `backend/src/main/java/cc/ivera/magnet/engine/piece/PeerMultiplexedRound.java`

1. Give Actor loops the same bounded round deadline.
2. Replace the immediate `null`-lease exit with a short interruptible wait and retry.
3. Preserve all existing mailbox-close, owner-interrupt, Peer-exclusive lease, and cleanup behavior.
4. Run the focused regression and nearby multiplexed/refresh tests.

## Task 3: Verify and deploy without losing progress

**Files:**
- Modify at closeout: `HANDOFF.md`

1. Run `mvn -q clean test` and `git diff --check`.
2. Read the live job state; pause it through the authenticated API only if it is still running.
3. Restart the backend on port 8082 with the rebuilt classes, prepare/resume the same task and checkpoint, then collect a bounded metrics sample.
4. Record exact commands, exit codes, observed rates/Peer metrics, and any remaining limitation in `HANDOFF.md`.
