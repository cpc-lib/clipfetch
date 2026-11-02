# Native Mailbox Active-Primary Index Design

## 1. Goal

Reduce lock-held scheduling work in Native BitTorrent downloads by making
overdue primary-lease reassignment scan only Blocks that currently have a
primary lease.

This is a scheduler/CPU contention optimization. It does not claim a
download-speed percentage without a comparable unfinished-download benchmark.

## 2. Current bottleneck

`PeerRoundConnectionPool.runMailbox()` checks overdue transfers at most once
per 250 ms for each connection. `PeerBlockMailbox.reassignOverdue()` then
scans the complete insertion-ordered `unfinished` set while holding the
mailbox monitor.

`unfinished` contains both fresh, unclaimed Blocks and Blocks with active
primary leases. A round can contain up to 32,768 Blocks, so a normal overdue
poll can inspect a large number of fresh Blocks that cannot be overdue. With
many connections, these repeated synchronized scans can consume CPU and hold
the mailbox lock while other actors are trying to claim or release work.

The existing log records predate the latest mailbox optimizations and are not a
post-change speed benchmark; they are only supporting evidence for this
hot-path hypothesis.

## 3. Chosen design

Add a mailbox-local insertion-ordered set:

```text
activePrimary = unfinished Blocks whose primary lease is non-null
```

`reassignOverdue()` will iterate `activePrimary` instead of `unfinished`.
The existing transfer limit, eligibility checks, timeout calculation,
insertion order, and synchronized mailbox monitor remain unchanged.

The index lifecycle is:

- constructor: start empty because every Block is unclaimed;
- fresh primary claim: add the Block after assigning its primary lease;
- accepted delivery: remove the completed Block;
- primary disconnect with a backup: keep the Block indexed after promoting the
  backup to primary;
- primary disconnect without a backup: remove the Block and re-add it to the
  existing fresh-primary index;
- backup disconnect: leave the Block indexed because its primary remains;
- overdue primary reassignment: leave the Block indexed because the Block
  still has a primary lease;
- end-game backup claim: leave the Block indexed because its primary remains.

No new configuration, public API, database field, network behavior, or
timeout is introduced.

## 4. Invariants and concurrency

- A Block is in `activePrimary` exactly when it is unfinished and has a
  non-null primary lease.
- A Block is never in both the fresh-primary index and `activePrimary`.
- `activePrimary` contains no completed Block.
- All reads and writes to the index happen inside the existing synchronized
  mailbox methods; no new cross-thread publication mechanism is needed.
- `reassignOverdue()` does not change the membership of `activePrimary` while
  iterating it, so its iterator remains valid under the existing monitor.
- `unfinished` remains the source for end-game backup selection and socket
  disconnect cleanup; this change does not remove or reorder those paths.
- Outstanding counts, prior-lease history, uncertain-socket handling,
  cancellation, stale responses, and Piece verification semantics remain
  unchanged.

## 5. Testing and acceptance

Focused mailbox tests will cover the index lifecycle and existing scheduling
behavior:

1. fresh primary claims create active-primary entries;
2. accepted delivery removes the entry;
3. primary disconnect with no backup removes the entry and makes the Block
   claimable again;
4. primary disconnect with a backup promotes the backup while retaining the
   entry;
5. overdue reassignment still transfers an eligible active primary lease.

The tests may inspect this package-private scheduler index directly rather
than adding a production-facing diagnostic method. This keeps the production
surface minimal while making the new invariant deterministic and testable.

Verification after implementation:

- focused mailbox/health/connection tests pass;
- `mvn -q clean test` passes;
- `mvn -q clean compile -DskipTests` passes;
- `git diff --check` passes;
- the repository no-BOM check passes;
- backend is restarted after Java changes;
- no download-speed improvement percentage is reported without a new,
  comparable unfinished Native task benchmark.

## 6. Explicit non-goals

- no change to peer count, request window, timeout, exploration, or
  connection-reuse settings;
- no replacement of the mailbox with a priority queue or time wheel;
- no cursor/budget behavior that could delay an eligible overdue transfer;
- no API, frontend, database, Flyway, or documentation changes outside the
  required design/hand-off records;
- no cleanup of unrelated existing code.
