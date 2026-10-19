package cc.ivera.magnet.engine.piece;

import java.net.InetSocketAddress;
import java.util.*;

/**
 * Owner-scoped Piece queue with one unique lease per Piece per round.
 * A task's persistent Peer actor claims a Piece and an eligible Peer together;
 * the actor's thread affinity and Peer Bitfield influence the choice.
 *
 * The queue does not own sockets, data, database state, or leases across tasks.
 * Bytes are still independently SHA-1 validated and committed by the owner.
 */
final class PeerCentricPieceQueue {
    private final List<Integer> pending;
    private final Set<Integer> assigned=new HashSet<>();
    record Assignment(int piece,InetSocketAddress peer) {}
    PeerCentricPieceQueue(List<Integer> scheduled) {
        if(scheduled==null || scheduled.isEmpty() ||
                scheduled.size()!=new HashSet<>(scheduled).size() ||
                scheduled.stream().anyMatch(p->p==null || p<0))
            throw new IllegalArgumentException("Invalid Piece queue");
        pending=new ArrayList<>(scheduled);
    }
    synchronized Assignment claim(PeerHealthTracker health,PeerPieceAvailability availability) {
        if(health==null || availability==null)
            throw new IllegalArgumentException("Missing scheduler context");
        if(pending.isEmpty())return null;
        var selected=health.leaseBestWork(pending,availability);
        int piece=selected==null?pending.get(0):selected.piece();
        var peer=selected==null?null:selected.peer();
        if(!pending.remove(Integer.valueOf(piece)) || !assigned.add(piece))
            throw new IllegalStateException("Piece leased twice");
        return new Assignment(piece,peer);
    }
    synchronized int remaining(){return pending.size();}
    synchronized int assigned(){return assigned.size();}
}
