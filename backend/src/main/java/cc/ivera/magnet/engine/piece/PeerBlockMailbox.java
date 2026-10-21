package cc.ivera.magnet.engine.piece;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.*;

/**
 * Task-local, peer-centric mailbox for outstanding BEP3 16KiB blocks.
 *
 * A block has exactly one primary and at most one timed-out end-game backup.
 * The first valid requested response wins; the other peer receives a CANCEL.
 * Payload is NOT hash-verified and MUST pass final Piece SHA-1 before a
 * checkpoint, CAS or verified-byte counter is advanced.
 *
 * This is an isolated scheduler core. Socket dispatch will be enabled only
 * after multi-Piece wire demultiplexing has passed interoperability tests.
 */
final class PeerBlockMailbox {
    static final int MAX_BLOCKS=32768;
    static final int DEFAULT_MAX_PEER_IN_FLIGHT=64;
    static final int MAX_ALLOWED_PEER_IN_FLIGHT=256;
    static final int ENDGAME_REMAINING=16;
    private static final long MIN_BACKUP_DELAY_NANOS=2_000_000_000L;
    // Public swarms show Block RTT P50~4s/P95~8s: a first-batch request needs
    // ~2 RTTs of grace before it can be called overdue, and even sampled Peers
    // deserve up to 4xRTT with a ceiling beyond the observed P95.
    private static final long MAX_BACKUP_DELAY_NANOS=24_000_000_000L;
    private static final long FIRST_SAMPLE_GRACE_NANOS=12_000_000_000L;
    private static final int MAX_TRANSFERS_PER_POLL=8;
    private static final int MAX_PEER_HISTORY_PER_BLOCK=4;

    record Key(int piece,int begin,int length) {
        Key {
            if(piece<0 || begin<0 || begin%PieceBlockPlanner.BLOCK_BYTES!=0 ||
                    length<1 || length>PieceBlockPlanner.BLOCK_BYTES)
                throw new IllegalArgumentException("Invalid Block identity");
        }
    }
    record Request(Key key,InetSocketAddress peer,boolean backup) {}
    record Transfer(Request request,Request cancel) {}
    record Winner(Key key,byte[] payload,List<Request> cancel) {
        Winner {
            payload=payload.clone();
            cancel=List.copyOf(cancel);
        }
    }
    enum Delivery { ACCEPTED, DUPLICATE }
    record Receipt(Delivery delivery,Winner winner) {}
    private static final class Lease {
        final InetSocketAddress peer;
        final long started;
        Lease(InetSocketAddress peer,long started) {this.peer=peer;this.started=started;}
    }
    private static final class Block {
        final Key key;
        Lease primary;
        Lease backup;
        boolean completed;
        final Set<InetSocketAddress> priorLeases=new HashSet<>();
        Block(Key key){this.key=key;}
    }
    private final LinkedHashMap<Key,Block> blocks=new LinkedHashMap<>();
    private final LinkedHashSet<Block> unfinished=new LinkedHashSet<>();
    private final LinkedHashSet<Block> activePrimary=new LinkedHashSet<>();
    private final Map<Integer,LinkedHashSet<Block>> unclaimedByPiece=new LinkedHashMap<>();
    // Hot actors poll every 2-15ms. Avoid O(all Blocks) work when no new
    // primary leases can be issued or an idle standby asks if it is useful.
    private final Map<Integer,Integer> remainingByPiece=new HashMap<>();
    private final LinkedHashSet<Integer> remainingPieceIndexes=new LinkedHashSet<>();
    private Set<Integer> remainingPieceSnapshot=Set.of();
    private int unclaimed;
    private final Map<InetSocketAddress,Integer> outstanding=new HashMap<>();
    private final Map<InetSocketAddress,Long> peerRttNanos=new HashMap<>();
    private final Set<InetSocketAddress> uncertainSockets=new HashSet<>();
    private final int maxPeerInFlight;
    private static final java.util.logging.Logger DIAGNOSTICS=
            java.util.logging.Logger.getLogger(PeerBlockMailbox.class.getName());
    private int completed;
    private long timedOutTransfers;
    private boolean closedForClaiming;
    private long staleResponses;

    PeerBlockMailbox(Collection<Key> wanted) {
        this(wanted,DEFAULT_MAX_PEER_IN_FLIGHT);
    }

    PeerBlockMailbox(Collection<Key> wanted,int maxPeerInFlight) {
        if(wanted==null || wanted.isEmpty() || wanted.size()>MAX_BLOCKS)
            throw new IllegalArgumentException("Invalid mailbox size");
        if(maxPeerInFlight<PeerBlockRequestWindow.INITIAL ||
                maxPeerInFlight>MAX_ALLOWED_PEER_IN_FLIGHT)
            throw new IllegalArgumentException("Invalid Peer mailbox window");
        this.maxPeerInFlight=maxPeerInFlight;
        for(var key:wanted) {
            if(key==null || blocks.putIfAbsent(key,new Block(key))!=null)
                throw new IllegalArgumentException("Duplicate or null Block");
            var block=blocks.get(key);
            unfinished.add(block);
            unclaimedByPiece.computeIfAbsent(key.piece(),ignored->new LinkedHashSet<>())
                    .add(block);
            remainingPieceIndexes.add(key.piece());
            remainingByPiece.merge(key.piece(),1,Integer::sum);
            unclaimed++;
        }
        refreshRemainingPieceSnapshot();
    }

    synchronized void observeRtt(InetSocketAddress peer,long nanos) {
        if(peer==null || nanos<=0)throw new IllegalArgumentException("Invalid Peer RTT");
        peerRttNanos.merge(peer,nanos,(old,value)->(7*old+value)/8);
    }
    record QueueStats(int total,int completed,int unclaimed,int primaryLeases,int backupLeases) {}
    synchronized QueueStats queueStats() {
        int unclaimed=0,primary=0,backup=0;
        for(var block:unfinished) {
            if(block.completed)continue;
            if(block.primary==null)unclaimed++;
            else primary++;
            if(block.backup!=null)backup++;
        }
        return new QueueStats(blocks.size(),completed,unclaimed,primary,backup);
    }
    synchronized long lastRttNanos(InetSocketAddress peer) {
        return peerRttNanos.getOrDefault(peer,0L);
    }
    synchronized int remaining(){return blocks.size()-completed;}
    /**
     * Round deadline reached: stop issuing new primary/backup leases while
     * every Actor finishes its already-outstanding transfers. Verified
     * Pieces still commit instead of discarding the whole round.
     */
    synchronized void closeForClaiming(){
        closedForClaiming=true;
        notifyAll();
    }
    synchronized void reopenForClaiming(){
        closedForClaiming=false;
        notifyAll();
    }
    synchronized boolean closedForClaiming(){return closedForClaiming;}
    /** Piece indices that still have at least one unfinished Block. */
    synchronized Set<Integer> remainingPieces(){
        return remainingPieceSnapshot;
    }
    synchronized long timedOutTransfers(){return timedOutTransfers;}
    synchronized long staleResponses(){return staleResponses;}
    synchronized int peerOutstanding(InetSocketAddress peer) {
        return outstanding.getOrDefault(peer,0);
    }
    synchronized void awaitStateChange(InetSocketAddress peer,long maxWaitNanos)
            throws InterruptedException {
        if(peer==null || maxWaitNanos<1)
            throw new IllegalArgumentException("Invalid mailbox wait");
        if(peerOutstanding(peer)>0 || closedForClaiming())return;
        long millis=java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(maxWaitNanos);
        int nanos=(int)(maxWaitNanos-
                java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(millis));
        wait(millis,nanos);
    }
    /** A temporarily idle actor is still a useful fallback if another Peer
     * owns Blocks that this Peer can supply after failure/end-game timeout.
     */
    synchronized boolean hasPotentialWork(InetSocketAddress peer,
                                          PeerPieceAvailability availability) {
        // Mirrors claim(): in-round taint does not make a Peer useless.
        if(peer==null || availability==null)return false;
        for(var piece:remainingByPiece.entrySet())
            if(piece.getValue()>0 && availability.couldHave(peer,piece.getKey()))
                return true;
        return false;
    }

    /**
     * Assign unclaimed blocks first. At the tail, offer a single redundant
     * request for an overdue primary on a DIFFERENT authenticated Peer.
     */
    synchronized List<Request> claim(InetSocketAddress peer,
                                     PeerPieceAvailability available,
                                     long nowNanos,int max) {
        if(peer==null || available==null || nowNanos<0 ||
                max<1 || max>maxPeerInFlight)
            throw new IllegalArgumentException("Invalid mailbox admission");
        int slots=Math.min(max,maxPeerInFlight-peerOutstanding(peer));
        var result=new ArrayList<Request>();
        // Uncertain sockets stay claimable inside the round: deliver() still
        // discards their stale replies via priorLeases, and blacklisting every
        // reassignment victim would idle the whole swarm before round end.
        // The taint only gates cross-round socket reuse (shouldRetire).
        if(slots<=0 || closedForClaiming)return result;
        // A pure insertion-order batch monopolizes a Peer on one Piece until
        // that Piece's requests drain. Reserve roughly 1/4 of each refill for
        // the next eligible Piece so the same TCP stream is already fetching
        // useful cross-Piece data before the previous SHA-1 finishes.
        int leadPiece=-1;
        if(unclaimed>0) {
            for(var entry:unclaimedByPiece.entrySet()) {
                int piece=entry.getKey();
                if(!available.couldHave(peer,piece))continue;
                for(var block:entry.getValue()) {
                    if(!block.completed && block.primary==null &&
                            !block.priorLeases.contains(peer)) {
                        leadPiece=piece;
                        break;
                    }
                }
                if(leadPiece>=0)break;
            }
        }
        if(unclaimed>0 && leadPiece>=0) {
            int leadQuota=slots<4?slots:Math.max(1,slots-slots/4);
            claimPrimary(peer,available,nowNanos,slots,leadPiece,true,leadQuota,result);
            claimPrimary(peer,available,nowNanos,slots,leadPiece,false,slots,result);
            // Single-Piece swarms should still fill the entire window.
            claimPrimary(peer,available,nowNanos,slots,leadPiece,true,slots,result);
        }
        // End-game only: do not duplicate large jobs while fresh work exists.
        if(remaining()<=ENDGAME_REMAINING) {
            for(var block:unfinished) {
                if(result.size()>=slots)break;
                if(block.completed || block.primary==null || block.backup!=null ||
                        block.primary.peer.equals(peer) ||
                        // This donor already has a cancelled lease. Use an
                        // exclusive timeout transfer instead of contaminating
                        // another clean socket with an end-game backup.
                        uncertainSockets.contains(block.primary.peer) ||
                        !available.couldHave(peer,block.key.piece()))continue;
                long observed=peerRttNanos.getOrDefault(block.primary.peer,0L);
                long timeout=observed<=0?MIN_BACKUP_DELAY_NANOS:
                        Math.max(MIN_BACKUP_DELAY_NANOS,
                                Math.min(MAX_BACKUP_DELAY_NANOS,4*observed));
                if(nowNanos-block.primary.started<timeout)continue;
                if(DIAGNOSTICS.isLoggable(java.util.logging.Level.FINE))
                    DIAGNOSTICS.fine("native_endgame_backup block="+block.key
                            +" primary="+block.primary.peer+" backup="+peer
                            +" elapsedNanos="+(nowNanos-block.primary.started)
                            +" remaining="+remaining());
                block.backup=new Lease(peer,nowNanos);
                block.priorLeases.add(peer);
                outstanding.merge(peer,1,Integer::sum);
                result.add(new Request(block.key,peer,true));
            }
        }
        return List.copyOf(result);
    }

    /**
     * Transfer old Block leases before end-game, but ONLY to another
     * authenticated Peer which advertises/could hold the Piece.
     * The replacement becomes the sole primary. The original is asked to
     * CANCEL and its socket is retired to prevent late-frame cross-round
     * contamination. Never steal a live request from the same Peer again.
     */
    synchronized List<Transfer> reassignOverdue(InetSocketAddress receiver,
                                                PeerPieceAvailability available,
                                                long nowNanos,int max) {
        if(receiver==null || available==null || nowNanos<0 ||
                max<0 || max>maxPeerInFlight)
            throw new IllegalArgumentException("Invalid overdue reassignment");
        int slots=Math.min(Math.min(max,MAX_TRANSFERS_PER_POLL),
                maxPeerInFlight-peerOutstanding(receiver));
        // A reassignment victim may hold stale in-flight bytes: it stays
        // claimable for FRESH blocks (claim/hasPotentialWork), but it must not
        // take over other Peers' leases, or two timed-out Peers would swap each
        // other's Blocks back and forth without ever completing a Piece.
        if(slots<=0 || uncertainSockets.contains(receiver))return List.of();
        var result=new ArrayList<Transfer>();
        for(var block:activePrimary) {
            if(result.size()>=slots)break;
            if(block.completed || block.primary==null || block.backup!=null ||
                    block.primary.peer.equals(receiver) ||
                    (remaining()<=ENDGAME_REMAINING &&
                            !uncertainSockets.contains(block.primary.peer)) ||
                    block.priorLeases.contains(receiver) ||
                    block.priorLeases.size()>=MAX_PEER_HISTORY_PER_BLOCK ||
                    !available.couldHave(receiver,block.key.piece()))continue;
            long rtt=peerRttNanos.getOrDefault(block.primary.peer,0L);
            long timeout=rtt<=0?FIRST_SAMPLE_GRACE_NANOS:
                    Math.max(MIN_BACKUP_DELAY_NANOS,
                            Math.min(MAX_BACKUP_DELAY_NANOS,4*rtt));
            if(nowNanos-block.primary.started<timeout)continue;
            InetSocketAddress previous=block.primary.peer;
            decrement(previous);
            block.primary=new Lease(receiver,nowNanos);
            block.priorLeases.add(receiver);
            outstanding.merge(receiver,1,Integer::sum);
            uncertainSockets.add(previous);
            timedOutTransfers++;
            result.add(new Transfer(new Request(block.key,receiver,false),
                    new Request(block.key,previous,false)));
        }
        if(!result.isEmpty())notifyAll();
        return List.copyOf(result);
    }

    private void claimPrimary(InetSocketAddress peer,PeerPieceAvailability available,
                              long nowNanos,int slots,int leadPiece,boolean lead,
                              int quota,List<Request> result) {
        if(lead) {
            claimPrimaryFromBucket(peer,nowNanos,slots,quota,
                    unclaimedByPiece.get(leadPiece),result);
            return;
        }
        for(var entry:unclaimedByPiece.entrySet()) {
            if(result.size()>=slots || result.size()>=quota)break;
            if(entry.getKey()==leadPiece ||
                    !available.couldHave(peer,entry.getKey()))continue;
            claimPrimaryFromBucket(peer,nowNanos,slots,quota,
                    entry.getValue(),result);
        }
    }

    private void claimPrimaryFromBucket(InetSocketAddress peer,long nowNanos,
                                        int slots,int quota,
                                        LinkedHashSet<Block> candidates,
                                        List<Request> result) {
        if(candidates==null)return;
        var iterator=candidates.iterator();
        while(iterator.hasNext() && result.size()<slots && result.size()<quota) {
            var block=iterator.next();
            if(block.completed || block.primary!=null) {
                iterator.remove();
                continue;
            }
            if(block.priorLeases.contains(peer))continue;
            iterator.remove();
            block.primary=new Lease(peer,nowNanos);
            block.priorLeases.add(peer);
            activePrimary.add(block);
            unclaimed--;
            outstanding.merge(peer,1,Integer::sum);
            result.add(new Request(block.key,peer,false));
        }
    }

    /** A duplicate can arise from a cancelled end-game loser. It is ignored. */
    synchronized Receipt deliver(InetSocketAddress peer,Key key,byte[] payload)
            throws IOException {
        if(peer==null || key==null || payload==null)
            throw new IllegalArgumentException("Missing Block delivery");
        var entry=blocks.get(key);
        if(entry==null || payload.length!=key.length())
            throw new IOException("Malformed or unsolicited Piece block");
        if(entry.completed) {
            if(!entry.priorLeases.contains(peer))
                throw new IOException("Unsolicited duplicate Piece block");
            if(uncertainSockets.contains(peer))staleResponses++;
            return new Receipt(Delivery.DUPLICATE,null);
        }
        boolean primary=entry.primary!=null && entry.primary.peer.equals(peer);
        boolean backup=entry.backup!=null && entry.backup.peer.equals(peer);
        if(!primary && !backup) {
            // In-flight TCP segments from a cancelled lease can still arrive
            // before CANCEL takes effect. Discard without admitting bytes to
            // the Piece assembler, hash, or verified-byte counters.
            if(entry.priorLeases.contains(peer) && uncertainSockets.contains(peer)) {
                staleResponses++;
                return new Receipt(Delivery.DUPLICATE,null);
            }
            throw new IOException("Peer sent unrequested Block");
        }
        Lease winning=primary?entry.primary:entry.backup;
        long elapsed=System.nanoTime()-winning.started;
        if(elapsed>0 && elapsed<java.util.concurrent.TimeUnit.SECONDS.toNanos(60))
            observeRtt(peer,elapsed);
        entry.completed=true;
        completed++;
        unfinished.remove(entry);
        activePrimary.remove(entry);
        int remaining=remainingByPiece.merge(key.piece(),-1,Integer::sum);
        if(remaining==0) {
            remainingPieceIndexes.remove(key.piece());
            refreshRemainingPieceSnapshot();
        }
        if(entry.primary!=null)decrement(entry.primary.peer);
        if(entry.backup!=null)decrement(entry.backup.peer);
        if(entry.primary!=null && entry.backup!=null) {
            // Late end-game replies may remain queued on BOTH sockets.
            // Neither can be safely reused in the next Piece round.
            uncertainSockets.add(entry.primary.peer);
            uncertainSockets.add(entry.backup.peer);
            if(DIAGNOSTICS.isLoggable(java.util.logging.Level.FINE))
                DIAGNOSTICS.fine("native_endgame_contamination block="+key
                        +" winner="+peer+" primary="+entry.primary.peer
                        +" backup="+entry.backup.peer);
        }
        var cancellations=new ArrayList<Request>();
        if(primary && entry.backup!=null)
            cancellations.add(new Request(key,entry.backup.peer,true));
        if(backup && entry.primary!=null)
            cancellations.add(new Request(key,entry.primary.peer,false));
        notifyAll();
        return new Receipt(Delivery.ACCEPTED,new Winner(key,payload,cancellations));
    }

    private void refreshRemainingPieceSnapshot() {
        remainingPieceSnapshot=Collections.unmodifiableSet(
                new LinkedHashSet<>(remainingPieceIndexes));
    }

    synchronized boolean shouldRetire(InetSocketAddress peer) {
        return uncertainSockets.contains(peer) || peerOutstanding(peer)>0;
    }

    /** Socket failure frees only this Peer's leases, not other Peer data. */
    synchronized void disconnect(InetSocketAddress peer) {
        if(peer==null)return;
        for(var block:unfinished) {
            if(block.completed)continue;
            if(block.primary!=null && block.primary.peer.equals(peer)) {
                decrement(peer);
                block.primary=block.backup;
                block.backup=null;
                if(block.primary==null) {
                    activePrimary.remove(block);
                    unclaimed++;
                    unclaimedByPiece.computeIfAbsent(block.key.piece(),
                            ignored->new LinkedHashSet<>()).add(block);
                }
            }else if(block.backup!=null && block.backup.peer.equals(peer)) {
                decrement(peer);
                block.backup=null;
            }
        }
        notifyAll();
    }
    private void decrement(InetSocketAddress peer) {
        int active=outstanding.getOrDefault(peer,0);
        if(active<=1)outstanding.remove(peer);
        else outstanding.put(peer,active-1);
    }
}
