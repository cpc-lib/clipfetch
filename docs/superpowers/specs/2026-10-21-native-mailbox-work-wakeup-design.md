# Native Mailbox Work Wakeup Design

## Goal

Reduce idle gaps in the Native multiplexed Peer read loop when a Peer has no outstanding Block requests, without increasing the Block window, Actor count, or CPU usage during a genuinely empty mailbox.

## Evidence and hypothesis

- `PeerRoundConnectionPool.runMailbox` currently calls `Thread.sleep(15)` whenever the current Peer has no outstanding Block requests.
- The same mailbox can change while that Actor sleeps: another Peer may complete a Block, a disconnected Peer may release leases, or an overdue reassignment may create work for an idle Peer.
- There is no current unfinished Native task for a before/after throughput comparison, so this is a code-level scheduling hypothesis, not a measured speed claim.

Hypothesis: notifying a waiting Peer actor on relevant mailbox state changes will reduce avoidable idle gaps while retaining the existing 15 ms fallback for missed or external state changes.

## Design

1. Add a package-local, synchronized `awaitStateChange(peer,maxWaitNanos)` operation to `PeerBlockMailbox`.
   - Return immediately if the Peer already has outstanding work or claiming is closed.
   - Otherwise wait on the mailbox monitor for at most the supplied bound.
   - Preserve interruption as the existing `IOException` cancellation path in `PeerRoundConnectionPool`.
2. Notify waiting actors after mailbox transitions that can change their next action:
   - an accepted Block delivery;
   - a Peer disconnect that releases or reopens leases;
   - an overdue reassignment that creates a request;
   - claiming close/reopen transitions.
3. Replace only the `Thread.sleep(15)` branch in `PeerRoundConnectionPool.runMailbox` with `awaitStateChange(...,15ms)`.
4. Keep the existing `hasPotentialWork` check, three-second idle exit, socket reads, cancellation handling, request window, and protocol behavior unchanged.

## Non-goals

- No change to `peer-max-in-flight-blocks`, Actor admission, Peer timeout values, or request-window growth.
- No new environment variable, thread, queue, persistence, or external service.
- No automatic resume or restart of paused/downloaded tasks.
- No claim of higher measured download speed without a comparable unfinished Native task.

## Testing

- Add a concurrency regression test that leases all mailbox Blocks to one Peer, waits on a second Peer, disconnects the first Peer, and verifies the wait returns before the 15 ms polling bound would be required.
- Keep existing `PeerHealthTrackerTest` and `PeerRoundConnectionPoolTest` green.
- Run `mvn -q clean test`, `mvn -q clean compile -DskipTests`, `git diff --check`, and the no-BOM scan.
- Restart the backend and verify port 8082 remains listening.

## Acceptance criteria

- The waiting Peer actor is notified by a mailbox state change and can retry immediately.
- A mailbox with no relevant state change still wakes within the same 15 ms bound.
- Existing Native scheduling and protocol tests pass.
- Runtime startup succeeds; throughput remains explicitly unmeasured until a repeatable unfinished task is available.
