# Native Idle HAVE Keepalive Design

## 1. Goal

Keep an authenticated Native BitTorrent connection alive long enough to
consume a queued HAVE message when its cached Bitfield currently says that it
cannot serve any unfinished Piece.

This is a narrow connection-lifetime fix. It does not claim a measured
download-speed percentage without a comparable unfinished Native task.

## 2. Root cause

PeerRoundConnectionPool.runMailbox() handles an idle socket in this order:

1. ask the mailbox whether the current cached availability can serve any
   unfinished Piece;
2. close the connection after three seconds when the answer is false;
3. only then poll already-buffered socket input for a control frame.

The cached Bitfield is refreshed only after a complete Bitfield or HAVE frame
is consumed. A Peer can therefore advertise no remaining Piece initially and
later send HAVE for one of them. If that HAVE is already buffered after the
three-second idle interval, the current order exits before consuming it. The
Peer is unnecessarily closed and must be connected and handshaken again.

## 3. Chosen design

Keep the existing idle wait and three-second retirement policy, but change the
decision order in runMailbox():

1. when the Peer has no outstanding Block, first check for buffered input;
2. poll one incremental frame;
3. when a complete frame is returned, update the advertised availability,
   refresh idleSince, and immediately restart the mailbox loop so the new
   revision is converted into cachedAvailability before admission;
4. when input is buffered but only a partial frame is available, keep the
   connection alive and let the existing bounded mailbox wait retry the read;
5. retire the idle connection only when the idle duration exceeds three
   seconds, no Piece is currently useful, no input was buffered, and no partial
   frame is being assembled.

Add one package-private predicate, shouldExitIdleMailbox, so the retirement
decision is directly regression-tested without exposing a production API or
requiring a network fixture. The predicate only expresses the existing
three-second policy plus the new queued-input/partial-frame guards.

## 4. Invariants and compatibility

- No change to Actor count, request window, timeout values, Piece selection,
  Bitfield semantics, protocol framing, persistence, or task state.
- A complete HAVE frame still updates link.advertised through the existing
  updateAdvertised method.
- A partial frame is never discarded; the existing incremental reader remains
  the owner of partial header/body state.
- The cached availability is rebuilt on the next loop iteration when the
  advertised revision changes.
- A genuinely idle Peer with no buffered or partial input still exits after
  the existing three-second threshold.

## 5. Testing and acceptance

Add focused tests to
backend/src/test/java/cc/ivera/magnet/engine/piece/PeerRoundConnectionPoolTest.java:

1. a queued frame prevents retirement even when cached availability reports no
   potential work;
2. a partial frame also prevents retirement;
3. no potential work, no queued input, and no partial frame still retires after
   the threshold.

TDD sequence:

- add the tests and observe the focused test compile/fail because the predicate
  does not exist;
- add the predicate and reorder the idle branch minimally;
- run the focused test, the Native focused regression set, full Maven tests,
  clean compile, git diff --check, and a no-BOM scan;
- restart the backend after Java changes and verify exactly one listener on
  port 8082;
- do not report a speed percentage without a new comparable unfinished task.

## 6. Non-goals

- no Actor/Peer admission changes;
- no remainingPieces() allocation optimization in this change;
- no new configuration key;
- no frontend, database, Flyway, API, or README changes;
- no automatic resume or modification of existing download tasks.
