# Native Actor Lease Wakeup Design

## Goal

Reduce Native multiplexed Actor scheduling delay and lock contention when no Peer is immediately leasable, while preserving the existing Peer admission, cooldown, availability, and round-local quarantine rules.

## Evidence and hypothesis

- `PeerMultiplexedRound` currently retries a failed `leaseExclusive` with a fixed sleep of 50–250ms.
- A Peer release or newly discovered Peer does not notify waiting Actors, so an available Peer may remain unused until the next polling interval.
- Historical Native diagnostics showed very high `noWorkWaits`; the current log is stale and there is no active unfinished task, so this is a code-level scheduling hypothesis rather than a post-fix throughput measurement.

Hypothesis: condition-based wakeup will let an Actor claim a released or newly discovered Peer immediately, reducing idle gaps without increasing the number of Actors or network requests.

## Design

1. Add a bounded `awaitLease` operation to `PeerHealthTracker`.
   - Attempt the existing exclusive lease first.
   - If no candidate is currently available, wait on the tracker monitor until a state-changing operation notifies it or the bounded wait expires.
   - Re-check the same availability and `failedThisRound` filters after every wakeup.
   - Preserve the existing controlled fallback to a failed Peer only when no non-failed candidate is leasable.
2. Notify waiting Actors after state changes that may create a lease:
   - `release`, `failure`, successful verified-Piece observation, discovered-Peer merge, and swarm amnesty.
3. Keep a maximum wait of 250ms for cancellation/deadline responsiveness and to handle missed external state changes. Thread interruption remains an `IOException` cancellation path in the Actor supervisor.
4. Replace only the Actor loop's sleep/retry block with `awaitLease`; leave failure accounting, socket handling, mailbox claims, and Actor target calculation unchanged.

## Non-goals

- No change to `peer-exploration-slots`, total Actor limits, Peer timeout values, or in-flight Block windows.
- No change to persisted task state or automatic resumption of paused downloads.
- No claim of higher measured download speed without a comparable unfinished Native task.

## Acceptance criteria

- A concurrency regression test demonstrates that a waiting Actor is released when an idle Peer is released, without waiting for the 250ms timeout.
- Lease filtering and failed-Peer fallback tests remain green.
- `mvn -q clean test`, `mvn -q clean compile -DskipTests`, `git diff --check`, and BOM scanning pass.
- The restarted backend listens on port 8082.
