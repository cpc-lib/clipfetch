# Native Exploration Window Design

## Goal

Reduce Native multiplexed download oversubscription by making the unknown-Peer exploration window explicit and lowering its default from 64 to 32 Actors.

## Evidence and hypothesis

- `PieceDownloadCoordinator.parallelMultiplexed` currently passes `Math.min(64, actorCap)` into Actor admission.
- Historical `native_multiplexed` diagnostics showed roughly 200 Actors alongside hundreds of failed Peers, timeout-dominant failures, and very high `noWorkWaits`.
- There is no post-fix unfinished download available for a before/after speed comparison, so this change is a controlled admission hypothesis rather than a measured throughput claim.

Hypothesis: a smaller exploration window reduces simultaneous dead/unresponsive Peer attempts and mailbox contention while preserving all verified-Peer slots. The first round still starts up to the configured exploration count when no Peer has yet produced a verified Piece.

## Design

1. Add `peer-exploration-slots` to `NativeTorrentProperties` with a default of `32`.
2. Bind `TORRENT_NATIVE_PEER_EXPLORATION_SLOTS` in `application.yml`, `.env.example`, and the active `backend/.env`.
3. Pass the configured value from `NativeTaskWorkerRoundService` to `PieceDownloadCoordinator`, which uses it only for multiplexed Actor target calculation.
4. Keep health admission, failed-Peer cooldown, mailbox in-flight limits, timeouts, and total Worker limits unchanged.
5. Reject non-positive values at the coordinator configuration boundary; `computeTargetActors` still caps the result by the existing Actor cap.

## Non-goals

- No change to the Ubuntu task or any persisted task state.
- No change to the Native legacy scheduler.
- No claim of higher measured download speed until an unfinished, repeatable task is run.

## Acceptance criteria

- A unit test proves the configured exploration value is used by the multiplexed admission calculation.
- The test is observed failing before the production wiring is added and passing afterward.
- `mvn -q clean test`, `mvn -q clean compile -DskipTests`, BOM scan, and `git diff --check` pass.
- The restarted backend loads and listens on port 8082.
