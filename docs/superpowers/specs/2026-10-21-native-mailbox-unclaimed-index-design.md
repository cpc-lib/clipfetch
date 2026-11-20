# Native Mailbox Unclaimed-Block Index Design

## 1. Goal

Reduce lock-held scheduling work in Native BitTorrent downloads by avoiding full
`unfinished` scans when a Peer requests fresh primary Block leases.

This change targets scheduler CPU/lock contention only. It does not claim a
network-speed improvement without a comparable unfinished-download benchmark.

## 2. Current bottleneck

`PeerBlockMailbox.claim()` currently scans the complete insertion-ordered
`unfinished` set to:

1. find an eligible lead Piece;
2. claim the lead-Piece quota;
3. claim the remaining fresh primary leases.

The mailbox can contain up to 32,768 Blocks. Most of those Blocks may already
have a primary lease while actors refill their bounded request windows, so the
same synchronized mailbox lock can be held while inspecting many ineligible
Blocks. A 250 ms throttle already limits overdue-transfer scans; this design
does not change that path.

## 3. Chosen design

Add a mailbox-local, insertion-ordered index:

```text
piece index -> insertion-ordered set of Blocks whose primary is null
```

The index contains only fresh primary candidates. `claim()` will:

- find the lead Piece by inspecting indexed Piece buckets and checking the
  Peer availability once per Piece before checking its Blocks;
- claim lead and non-lead primary leases from those buckets while preserving
  the current lead quota and cross-Piece refill behavior;
- leave end-game backup selection on `unfinished`, because at that point the
  mailbox has at most `ENDGAME_REMAINING` unfinished Blocks;
- leave overdue reassignment on `unfinished`, because it intentionally needs
  leased Blocks and is already throttled by the connection loop.

The index is maintained under the existing mailbox monitor:

- constructor: add every Block to its Piece bucket;
- primary claim: remove the Block from its bucket;
- primary disconnect with no backup: add the Block back to its bucket;
- accepted delivery: no index change; an accepted Block always has a primary or
  backup lease and therefore is not in the fresh-primary index;
- backup disconnect and overdue transfer: no index change because the primary
  lease remains.

Empty Piece buckets may remain for the lifetime of the mailbox. The set of
  Piece keys is bounded by the current round, and retaining empty buckets avoids
  mutating the outer index while iterating it.

## 4. Invariants and compatibility

- `unclaimed` equals the total number of Blocks in all indexed buckets.
- A Block is indexed exactly when it is unfinished and has no primary lease.
- A Block is never claimed from a bucket if it is completed, already leased, or
  present in that Peer’s `priorLeases`.
- `remainingByPiece`, `unfinished`, outstanding counts, cancellation handling,
  stale-response handling, and Peer health semantics remain unchanged.
- All index reads and writes happen inside existing synchronized mailbox
  methods; no new cross-thread publication mechanism is introduced.
- Unknown Peer availability remains eligible, while a known-false Piece is
  skipped at the Piece-bucket level.
- The existing lead-Piece quota and single-Piece fill behavior remain intact.

## 5. Testing and acceptance

Focused mailbox tests exercise the observable scheduling behavior:

1. a Peer with a known-empty bitfield checks availability once per Piece bucket
   instead of once per Block;
2. disconnecting a primary Peer re-exposes its Blocks to the indexed fresh-work
   path and a second Peer can claim them.

Existing delivery, duplicate, and stale-response semantics remain outside this
index-only regression and are not changed by the implementation.

Verification:

- run the focused mailbox/health/connection tests;
- run `mvn -q clean test`;
- run `mvn -q clean compile -DskipTests`;
- run `git diff --check` and the repository no-BOM check;
- do not report a download-speed percentage without a new, unfinished,
  comparable magnet benchmark.

## 6. Explicit non-goals

- no per-Peer queues;
- no change to connection count, request window, timeout, or Peer selection;
- no new configuration keys;
- no database, API, frontend, or Flyway changes;
- no cleanup of unrelated pre-existing code.
