package cc.ivera.magnet.engine.piece;

import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Per-round bounded Peer cooldown. Success is recorded only for SHA-1-verified Piece data. */
final class PeerHealthTracker {
    private static final long BASE_NANOS=250_000_000L;
    private static final long DEFAULT_MAX_NANOS=30_000_000_000L;
    private static final long MAX_ALLOWED_NANOS=60_000_000_000L;
    private static final class Health {
        int successes, failures, consecutiveFailures;
        int inFlight;
        long estimatedKiBps;
        long blockRttP95Nanos;
        int slowObservations;
        long cooldownUntil;
    }
    private final List<InetSocketAddress> peers;
    private final Map<InetSocketAddress,Health> records=new HashMap<>();
    // Round-local socket affinity: prefer a proven Peer already connected on this
    // network thread, without sharing a TCP session between threads or owners.
    private final Map<Thread,InetSocketAddress> preferredByThread=new WeakHashMap<>();
    private final LongSupplier clock;
    private final long maxCooldownNanos;
    private AdaptivePeerConcurrency admission;
    private int completedRefreshes;

    PeerHealthTracker(List<InetSocketAddress> peers) {
        this(peers,System::nanoTime,DEFAULT_MAX_NANOS/1_000_000L);
    }
    PeerHealthTracker(List<InetSocketAddress> peers,LongSupplier clock) {
        this(peers,clock,DEFAULT_MAX_NANOS/1_000_000L);
    }
    PeerHealthTracker(List<InetSocketAddress> peers,LongSupplier clock,
                      long maxCooldownMillis) {
        if(peers==null || peers.isEmpty() || clock==null)
            throw new IllegalArgumentException("Invalid Peer health tracker");
        if(maxCooldownMillis<BASE_NANOS/1_000_000L ||
                maxCooldownMillis>MAX_ALLOWED_NANOS/1_000_000L)
            throw new IllegalArgumentException("Invalid Peer cooldown limit");
        this.peers=new ArrayList<>(new LinkedHashSet<>(peers));
        this.clock=clock;
        this.maxCooldownNanos=TimeUnit.MILLISECONDS.toNanos(maxCooldownMillis);
        this.admission=new AdaptivePeerConcurrency();
        for(var peer:this.peers)records.put(peer,new Health());
    }

    /** Carry bounded scores for matching endpoints across rounds of the same owner/task. */
    PeerHealthTracker(List<InetSocketAddress> peers,PeerHealthTracker previous) {
        this(peers,System::nanoTime,previous==null?DEFAULT_MAX_NANOS/1_000_000L:
                TimeUnit.NANOSECONDS.toMillis(previous.maxCooldownNanos));
        inherit(previous);
    }
    PeerHealthTracker(List<InetSocketAddress> peers,PeerHealthTracker previous,
                      long maxCooldownMillis) {
        this(peers,System::nanoTime,maxCooldownMillis);
        inherit(previous);
    }
    private void inherit(PeerHealthTracker previous) {
        if(previous!=null) synchronized(previous) {
            // Previous round is no longer running: inherit its adaptive target
            // but never inherit transient in-flight network reservations.
            admission=new AdaptivePeerConcurrency(previous.admission);
            completedRefreshes=previous.completedRefreshes;
            // Actor threads survive adjacent rounds in the same authorized
            // task; preserve their proven TCP affinity instead of randomizing
            // the first Peer lease after every round.
            for(var preferred:previous.preferredByThread.entrySet())
                if(records.containsKey(preferred.getValue()))
                    preferredByThread.put(preferred.getKey(),preferred.getValue());
            for(var entry:records.entrySet()) {
                var old=previous.records.get(entry.getKey());
                if(old==null)continue;
                var current=entry.getValue();
                current.successes=old.successes;
                current.estimatedKiBps=old.estimatedKiBps;
                current.blockRttP95Nanos=old.blockRttP95Nanos;
                current.slowObservations=old.slowObservations;
                current.failures=old.failures;
                current.consecutiveFailures=old.consecutiveFailures;
                current.cooldownUntil=old.cooldownUntil;
            }
        }
    }

    synchronized int targetWindow() {return admission.currentTarget();}
    synchronized int peerCount() {return peers.size();}
    synchronized int verifiedPeerCount() {return provenPeers().size();}
    synchronized int healthyVerifiedPeerCount() {
        int count=0;
        for(var h:records.values())
            if(h.successes>0 && h.consecutiveFailures==0)count++;
        return count;
    }
    synchronized int completedRefreshes() {return completedRefreshes;}
    synchronized List<InetSocketAddress> snapshotPeers() {return List.copyOf(peers);}
    synchronized List<InetSocketAddress> provenPeers() {
        var proven=new ArrayList<InetSocketAddress>();
        for(var peer:peers)
            if(records.get(peer).successes>0)proven.add(peer);
        return List.copyOf(proven);
    }
    synchronized int degradedCount() {
        int count=0;
        for(var h:records.values())
            if(h.consecutiveFailures>=2 || (h.failures>=3 && h.successes==0))count++;
        return count;
    }
    synchronized boolean needsRefresh(List<Integer> wanted,PeerPieceAvailability availability) {
        int eligible=eligibleCount(wanted,availability);
        return eligible<Math.min(8,peers.size()) ||
                degradedCount()>=Math.max(3,peers.size()/4);
    }
    /** Keep proven Peers. Replace repeated-failure, idle endpoints even when not at capacity. */
    synchronized int mergeDiscovered(List<InetSocketAddress> incoming,int limit) {
        if(incoming==null || limit<1 || limit>256)
            throw new IllegalArgumentException("Invalid dynamic Peer update");
        if(!incoming.isEmpty())completedRefreshes++;
        int added=0;
        for(var peer:new LinkedHashSet<>(incoming)) {
            if(peer==null || peer.getPort()<1 || peer.getPort()>65535 ||
                    records.containsKey(peer))continue;
            // Evict failed idle Peers even when pool is not full, so long as
            // limit is not already exceeded.
            InetSocketAddress victim=null;
            for(var old:peers) {
                Health h=records.get(old);
                if(h.inFlight==0 && h.successes==0 && h.consecutiveFailures>=2) {
                    if(victim==null || h.failures>records.get(victim).failures)
                        victim=old;
                }
            }
            if(victim!=null) {
                peers.remove(victim);
                records.remove(victim);
                InetSocketAddress removed=victim;
                preferredByThread.values().removeIf(removed::equals);
            } else if(peers.size()>=limit) {
                break;
            }
            peers.add(peer);
            records.put(peer,new Health());
            added++;
        }
        if(added>0)notifyAll();
        return added;
    }

    /** Eligible means not cooling down and not known to lack every selected Piece. */
    synchronized int eligibleCount(List<Integer> wanted,PeerPieceAvailability availability) {
        if(wanted==null || availability==null)throw new IllegalArgumentException("Missing availability");
        int count=0;
        long now=clock.getAsLong();
        for(var peer:peers) {
            if(now<records.get(peer).cooldownUntil)continue;
            for(int piece:wanted) {
                if(availability.couldHave(peer,piece)) {count++;break;}
            }
        }
        return count;
    }

    synchronized int adaptiveSlots(int configured,int memorySlots,List<Integer> wanted,
                                   PeerPieceAvailability availability) {
        return admission.slots(configured,eligibleCount(wanted,availability),wanted.size(),memorySlots);
    }
    synchronized void observeRound(int attempted,int verified) {admission.observe(attempted,verified);}

    /** A recent, task-local Block tail sample can demote a high-latency Peer.
     * No socket or user state is shared across torrent task ownership.
     */
    synchronized void observeBlockRtt(InetSocketAddress peer,long p95Nanos) {
        Health h=records.get(peer);
        if(h!=null && p95Nanos>0)
            h.blockRttP95Nanos=Math.min(p95Nanos,10_000_000_000L);
    }
    private static long performanceScore(Health h) {
        long throughput=Math.min(100_000L,h.estimatedKiBps)*40_000L;
        long proof=Math.min(20,h.successes)*250_000L;
        long historicalFailures=Math.min(20,h.failures)*2_000_000L;
        long tailMs=Math.min(2000,h.blockRttP95Nanos/1_000_000L);
        return throughput+proof-historicalFailures
                -tailMs*30_000L-h.slowObservations*6_000_000L;
    }

    /** Returns null rather than retrying a cooling Peer. */
    synchronized InetSocketAddress select(int piece,Set<InetSocketAddress> tried) {
        return select(piece,tried,null);
    }
    synchronized InetSocketAddress select(int piece,Set<InetSocketAddress> tried,
                                           PeerPieceAvailability availability) {
        long now=clock.getAsLong(),best=Long.MIN_VALUE;
        InetSocketAddress chosen=null;
        for(int i=0;i<peers.size();i++) {
            var peer=peers.get(i);
            if(tried.contains(peer) || (availability!=null &&
                    !availability.couldHave(peer,piece)))continue;
            var health=records.get(peer);
            if(now<health.cooldownUntil)continue;
            long rank=performanceScore(health)-Math.floorMod(i-piece,peers.size());
            if(availability!=null && availability.confirmed(peer,piece))
                rank+=20_000_000L; // prefer Bitfield-positive peers to unknown peers
            if(chosen==null || rank>best) {chosen=peer;best=rank;}
        }
        return chosen;
    }
    record Work(int piece,InetSocketAddress peer) {}
    /**
     * Peer-first admission: favor an existing authenticated socket on this
     * actor thread, then positive Bitfield information, throughput and idle
     * capacity. A cooling Peer or known-negative Piece is never leased.
     * Pending Pieces are already ordered by rarest-first when enabled.
     */
    synchronized Work leaseBestWork(List<Integer> pending,PeerPieceAvailability availability) {
        if(pending==null || availability==null)
            throw new IllegalArgumentException("Missing work selection");
        InetSocketAddress chosen=null;
        int selected=-1;
        long best=Long.MIN_VALUE;
        long now=clock.getAsLong();
        var preferred=preferredByThread.get(Thread.currentThread());
        for(int pi=0;pi<peers.size();pi++) {
            var peer=peers.get(pi);
            var h=records.get(peer);
            if(now<h.cooldownUntil)continue;
            for(int wi=0;wi<pending.size();wi++) {
                int piece=pending.get(wi);
                if(!availability.couldHave(peer,piece))continue;
                // Strongly prefer the same authenticated socket when Peers
                // perform comparably, but never let affinity override a
                // much faster Peer or one with substantially lower Block RTT.
                long score=performanceScore(h)-200_000_000L*h.inFlight
                        +(peer.equals(preferred)?8_000_000L:0)
                        +(availability.confirmed(peer,piece)?20_000_000L:0)
                        -wi*1024L-pi;
                if(chosen==null || score>best) {
                    best=score;
                    chosen=peer;
                    selected=piece;
                }
                // Every subsequent Piece for this Peer has lower queue priority.
                // Avoid a quadratic scan of its entire pending list.
                if(availability.confirmed(peer,piece))break;
            }
        }
        if(chosen==null)return null;
        records.get(chosen).inFlight++;
        return new Work(selected,chosen);
    }

    /** Spread parallel Piece requests across live Peers before reusing an endpoint. */
    synchronized InetSocketAddress lease(int piece,Set<InetSocketAddress> tried,
                                         PeerPieceAvailability availability) {
        long now=clock.getAsLong(),best=Long.MIN_VALUE;
        InetSocketAddress chosen=null;
        for(int i=0;i<peers.size();i++) {
            var peer=peers.get(i);
            if(tried.contains(peer) || (availability!=null &&
                    !availability.couldHave(peer,piece)))continue;
            var h=records.get(peer);
            if(now<h.cooldownUntil)continue;
            long rank=performanceScore(h)-200_000_000L*h.inFlight
                    -Math.floorMod(i-piece,peers.size());
            if(peer.equals(preferredByThread.get(Thread.currentThread())))
                rank+=8_000_000L;
            if(availability!=null && availability.confirmed(peer,piece))
                rank+=20_000_000L;
            if(chosen==null || rank>best) {chosen=peer;best=rank;}
        }
        if(chosen!=null)records.get(chosen).inFlight++;
        return chosen;
    }
    synchronized void release(InetSocketAddress peer) {
        var h=records.get(peer);
        if(h!=null && h.inFlight>0) {
            h.inFlight--;
            if(h.inFlight==0)notifyAll();
        }
    }

    /**
     * Exclusive lease for Actor supervisor: only succeeds when the Peer is
     * completely idle (inFlight == 0). Returns null when no idle Peer is
     * available, ensuring the same Peer is never leased to two Actors.
     */
    synchronized InetSocketAddress leaseExclusive(int piece,Set<InetSocketAddress> tried,
                                                  PeerPieceAvailability availability) {
        long now=clock.getAsLong(),best=Long.MIN_VALUE;
        InetSocketAddress chosen=null;
        for(int i=0;i<peers.size();i++) {
            var peer=peers.get(i);
            if(tried.contains(peer) || (availability!=null &&
                    !availability.couldHave(peer,piece)))continue;
            var h=records.get(peer);
            if(now<h.cooldownUntil || h.inFlight>0)continue; // busy or cooling
            long rank=performanceScore(h)-Math.floorMod(i-piece,peers.size());
            if(peer.equals(preferredByThread.get(Thread.currentThread())))
                rank+=8_000_000L;
            if(availability!=null && availability.confirmed(peer,piece))
                rank+=20_000_000L;
            if(chosen==null || rank>best) {chosen=peer;best=rank;}
        }
        if(chosen!=null)records.get(chosen).inFlight++;
        return chosen;
    }
    synchronized InetSocketAddress leaseExclusive(Set<Integer> pieces,
                                                   PeerPieceAvailability availability) {
        if(pieces==null || pieces.isEmpty())return null;
        return leaseExclusive(pieces,Set.of(),availability);
    }
    /** Delay until an idle Peer that could serve the remaining Pieces exits cooldown. */
    synchronized long nextLeaseDelayNanos(Set<Integer> pieces,
                                          PeerPieceAvailability availability) {
        if(pieces==null || pieces.isEmpty())return 0;
        long now=clock.getAsLong(),earliest=Long.MAX_VALUE;
        for(var entry:records.entrySet()) {
            var health=entry.getValue();
            if(health.inFlight>0)continue;
            boolean canServe=false;
            for(int piece:pieces)
                if(availability==null || availability.couldHave(entry.getKey(),piece)) {
                    canServe=true;break;
                }
            if(!canServe)continue;
            earliest=Math.min(earliest,Math.max(0,health.cooldownUntil-now));
        }
        return earliest;
    }
    /**
     * Exclusive lease for the Actor supervisor: leases an idle Peer
     * (inFlight == 0) that could serve at least one of the remaining Pieces.
     * Returns null when no such Peer is available, guaranteeing the same Peer
     * is never leased to two Actors simultaneously.
     */
    synchronized InetSocketAddress leaseExclusive(Set<Integer> pieces,
                                                  Set<InetSocketAddress> tried,
                                                  PeerPieceAvailability availability) {
        return leaseExclusive(pieces,tried,availability,Set.of(),false);
    }
    synchronized InetSocketAddress leaseExclusive(Set<Integer> pieces,
                                                  PeerPieceAvailability availability,
                                                  Set<InetSocketAddress> failedThisRound) {
        if(failedThisRound==null)
            throw new IllegalArgumentException("Missing failed Peer set");
        return leaseExclusive(pieces,Set.of(),availability,failedThisRound,true);
    }
    record LeaseWaitResult(InetSocketAddress peer,boolean missedInitialLease) {}
    record LeaseScanResult(InetSocketAddress peer,long nextLeaseDelayNanos) {}
    synchronized LeaseScanResult scanLease(Set<Integer> pieces,
                                            PeerPieceAvailability availability,
                                            Set<InetSocketAddress> failedThisRound) {
        if(failedThisRound==null)
            throw new IllegalArgumentException("Missing failed Peer set");
        return scanLease(pieces,Set.of(),availability,failedThisRound,true,true);
    }
    static long boundedLeaseWaitNanos(long remainingNanos,long nextLeaseDelayNanos) {
        if(remainingNanos<1)return 0;
        if(nextLeaseDelayNanos<1)
            return Math.min(remainingNanos,TimeUnit.MILLISECONDS.toNanos(1));
        return Math.min(remainingNanos,nextLeaseDelayNanos);
    }
    /** Wait for a state change that may make an exclusive Peer lease available. */
    synchronized LeaseWaitResult awaitLeaseWithStatus(Set<Integer> pieces,
                                                       PeerPieceAvailability availability,
                                                       Set<InetSocketAddress> failedThisRound,
                                                       long maxWaitNanos) throws InterruptedException {
        if(failedThisRound==null || maxWaitNanos<1)
            throw new IllegalArgumentException("Invalid lease wait");
        long deadline=System.nanoTime()+maxWaitNanos;
        boolean missedInitialLease=false;
        while(true) {
            var scan=scanLease(pieces,availability,failedThisRound);
            if(scan.peer()!=null)
                return new LeaseWaitResult(scan.peer(),missedInitialLease);
            missedInitialLease=true;
            long remaining=deadline-System.nanoTime();
            if(remaining<=0)return new LeaseWaitResult(null,true);
            long waitNanos=boundedLeaseWaitNanos(remaining,
                    scan.nextLeaseDelayNanos());
            if(waitNanos<1)return new LeaseWaitResult(null,true);
            long waitMillis=TimeUnit.NANOSECONDS.toMillis(waitNanos);
            int waitNanosRemainder=(int)(waitNanos-
                    TimeUnit.MILLISECONDS.toNanos(waitMillis));
            wait(waitMillis,waitNanosRemainder);
        }
    }
    synchronized InetSocketAddress awaitLease(Set<Integer> pieces,
                                               PeerPieceAvailability availability,
                                               Set<InetSocketAddress> failedThisRound,
                                               long maxWaitNanos) throws InterruptedException {
        return awaitLeaseWithStatus(pieces,availability,failedThisRound,maxWaitNanos).peer();
    }
    private InetSocketAddress leaseExclusive(Set<Integer> pieces,
                                             Set<InetSocketAddress> tried,
                                             PeerPieceAvailability availability,
                                             Set<InetSocketAddress> excluded,
                                             boolean allowExcludedFallback) {
        return scanLease(pieces,tried,availability,excluded,
                allowExcludedFallback,false).peer();
    }
    private LeaseScanResult scanLease(Set<Integer> pieces,
                                      Set<InetSocketAddress> tried,
                                      PeerPieceAvailability availability,
                                      Set<InetSocketAddress> excluded,
                                      boolean allowExcludedFallback,
                                      boolean includeDelay) {
        if(pieces==null || pieces.isEmpty())
            return new LeaseScanResult(null,0);
        int representative=pieces.iterator().next();
        long now=clock.getAsLong();
        long earliest=Long.MAX_VALUE;
        long best=Long.MIN_VALUE,fallbackBest=Long.MIN_VALUE;
        InetSocketAddress chosen=null,fallback=null;
        for(int i=0;i<peers.size();i++) {
            var peer=peers.get(i);
            boolean excludedPeer=excluded.contains(peer);
            var h=records.get(peer);
            if(includeDelay) {
                if(h.inFlight>0)continue;
            } else {
                if(tried.contains(peer) ||
                        (excludedPeer && !allowExcludedFallback) ||
                        now<h.cooldownUntil || h.inFlight>0)continue;
            }
            boolean canServe=false;
            boolean confirmed=false;
            for(int piece:pieces) {
                if(availability==null) {
                    canServe=true;
                    break;
                }
                var state=availability.state(peer,piece);
                if(state==PeerPieceAvailability.PieceState.UNKNOWN) {
                    canServe=true;
                    break;
                }
                if(state==PeerPieceAvailability.PieceState.PRESENT) {
                    canServe=true;
                    confirmed=true;
                    break;
                }
            }
            if(!canServe)continue;
            if(includeDelay)
                earliest=Math.min(earliest,Math.max(0,h.cooldownUntil-now));
            if(includeDelay &&
                    (tried.contains(peer) ||
                     (excludedPeer && !allowExcludedFallback) ||
                     now<h.cooldownUntil))continue;
            long rank=performanceScore(h)-Math.floorMod(i-representative,peers.size());
            if(peer.equals(preferredByThread.get(Thread.currentThread())))
                rank+=8_000_000L;
            if(confirmed)rank+=20_000_000L;
            if(excludedPeer) {
                if(fallback==null || rank>fallbackBest) {
                    fallback=peer;fallbackBest=rank;
                }
            } else if(chosen==null || rank>best) {
                chosen=peer;best=rank;
            }
        }
        var selected=chosen!=null?chosen:fallback;
        if(selected!=null)records.get(selected).inFlight++;
        return new LeaseScanResult(selected,earliest);
    }
    synchronized void success(InetSocketAddress peer) {
        var h=records.get(peer);if(h==null)return;
        h.successes=Math.min(1000,h.successes+1);
        h.consecutiveFailures=0;h.cooldownUntil=0;
        preferredByThread.put(Thread.currentThread(),peer);
        notifyAll();
    }
    /** Update a bounded EWMA only after a fully hash-verified Piece. */
    synchronized void success(InetSocketAddress peer,long bytes,long elapsedNanos) {
        success(peer);
        var h=records.get(peer);
        if(h==null || bytes<1 || elapsedNanos<1)return;
        long speed=Math.min(100_000L,(long)(bytes*1_000_000_000.0/
                (1024.0*elapsedNanos)));
        h.estimatedKiBps=h.estimatedKiBps==0?speed:
                (3*h.estimatedKiBps+speed)/4;
        long fastestOther=0;
        for(var entry:records.entrySet())
            if(!entry.getKey().equals(peer) && entry.getValue().successes>0)
                fastestOther=Math.max(fastestOther,entry.getValue().estimatedKiBps);
        // Soft demotion, never a hard ban: rare Piece availability may still
        // require this slow but healthy Peer, and recovery remains possible.
        if(fastestOther>=256 && h.estimatedKiBps*4<fastestOther)
            h.slowObservations=Math.min(8,h.slowObservations+1);
        else h.slowObservations=Math.max(0,h.slowObservations-1);
    }
    synchronized void observeSession(InetSocketAddress peer,long bytes,
                                     long elapsedNanos,int verifiedPieces) {
        if(verifiedPieces<1)return;
        if(bytes>0 && elapsedNanos>0)success(peer,bytes,elapsedNanos);
        else success(peer);
    }
    synchronized void failure(InetSocketAddress peer) {
        var h=records.get(peer);if(h==null)return;
        h.failures=Math.min(1000,h.failures+1);
        preferredByThread.remove(Thread.currentThread(),peer);
        h.consecutiveFailures=Math.min(8,h.consecutiveFailures+1);
        long delay=Math.min(maxCooldownNanos,BASE_NANOS << (h.consecutiveFailures-1));
        long now=clock.getAsLong();
        h.cooldownUntil=now>Long.MAX_VALUE-delay?Long.MAX_VALUE:now+delay;
        notifyAll();
    }

    /**
     * Break a self-reinforcing outage. When a whole round produced no verified
     * Piece, the swarm is churning or every Peer is cooling: keeping the full
     * exponential cooldown would lock out Peers that have since recovered or
     * that a fresh Tracker/DHT pass just confirmed. Lift every cooldown so the
     * next round retries them once. Failure counters are deliberately kept so
     * a retry that fails again re-enters cooldown AND still counts toward the
     * {@link #mergeDiscovered} eviction of Peers that never succeeded.
     */
    synchronized void grantSwarmAmnesty() {
        for(var h:records.values())h.cooldownUntil=0;
        notifyAll();
    }
}
