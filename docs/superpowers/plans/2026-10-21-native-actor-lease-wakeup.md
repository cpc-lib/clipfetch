# Native Actor Lease Wakeup Implementation Plan

## Goal

Reduce Native multiplexed Actor scheduling gaps by waking waiting Actors when a Peer lease can become available, while preserving the existing admission, cooldown, availability, fallback, and round-local quarantine behavior.

## Architecture

Keep the change inside the existing Native BitTorrent scheduling path. `PeerHealthTracker` owns the monitor and lease state; `PeerMultiplexedRound` waits through that tracker instead of polling with `Thread.sleep`. Peer state transitions notify the monitor. No new thread, queue, persistence, external service, or configuration parameter is introduced.

## Tech Stack

Java 21, Spring Boot, JUnit 5, Maven, existing Native BitTorrent engine.

## Tasks

- [x] Add a concurrency regression test in `PeerHealthTrackerTest` that holds the only eligible Peer, starts a waiting lease request, releases the Peer, and verifies the request wakes before the bounded wait expires.
  - Verify with the focused test command; the first run is expected to fail to compile because `awaitLease` does not exist yet.

- [x] Add the minimal condition-based lease wait to `PeerHealthTracker`.
  - Reuse the existing exclusive lease and failed-Peer fallback rules on every wakeup.
  - Notify waiting Actors after release, failure, successful verified-piece admission, discovered-Peer merge, and swarm amnesty.
  - Keep the wait bounded at 250 ms and preserve interrupt behavior.
  - Verify with the focused tracker tests.

- [x] Replace only the null-lease polling sleep in `PeerMultiplexedRound` with the tracker wait.
  - Preserve `noWorkWaits`, failed-peer accounting, quiet-stall termination, Actor limits, and all socket/session behavior.
  - Verify with focused Native scheduling tests.

- [x] Run repository verification and runtime checks.
  - `mvn -q clean test`
  - `mvn -q clean compile -DskipTests`
  - `git diff --check`
  - UTF-8 BOM scan for changed text files.
  - Restart the backend because Java code changed and verify port 8082 is listening.

- [x] Replace the root `HANDOFF.md` with the actual results, exit codes, remaining limitations, and next step.

## Scope Boundaries

- Do not change exploration slots, Actor limits, Peer timeout values, in-flight Block windows, or database state.
- Do not claim a throughput improvement without a comparable unfinished download benchmark.
- Do not create a commit.
