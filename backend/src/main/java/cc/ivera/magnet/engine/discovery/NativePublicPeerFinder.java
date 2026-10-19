package cc.ivera.magnet.engine.discovery;

import cc.ivera.magnet.engine.metainfo.VerifiedSwarmPrivacy;
import cc.ivera.magnet.engine.peer.PeerCandidatePool;
import cc.ivera.magnet.engine.piece.NativeTaskWorkerRoundService;
import cc.ivera.magnet.engine.tracker.TrackerAddressPolicy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Random;
import java.nio.ByteBuffer;
import cc.ivera.magnet.engine.piece.NativeNetworkBudget;

/**
 * Verified PUBLIC torrent -> bounded public Tracker, optionally DHT bootstrap when
 * no Tracker peer is available -> bounded PeX from discovered peers.
 *
 * No LSD, arbitrary tracker URLs, anonymous public swarm claims, or background loops.
 * DHT can disclose the InfoHash, so it requires an explicit separate opt-in.
 */
public final class NativePublicPeerFinder implements NativeTaskWorkerRoundService.PublicPeerFinder {
    @FunctionalInterface
    public interface TrackerSource {
        List<InetSocketAddress> collect(byte[] hash,byte[] peerId,int listenPort)
                throws IOException;
    }
    @FunctionalInterface
    public interface OwnerTrackerSource {
        List<InetSocketAddress> collect(List<java.net.URI> verifiedTaskTrackers,
                byte[] hash,byte[] peerId,int listenPort) throws IOException;
    }
    @FunctionalInterface
    public interface DhtSource {
        List<InetSocketAddress> collect(byte[] localNodeId,byte[] infoHash) throws IOException;
    }
    @FunctionalInterface
    public interface DhtPermission {
        boolean enabled();
    }

    private static final SecureRandom RANDOM=new SecureRandom();
    private final TrackerSource trackers;
    private final OwnerTrackerSource ownerTrackers;
    private final PeerDiscoveryAccelerator accelerator;
    private final TrackerAddressPolicy policy;
    private final DhtSource dht;
    private final DhtPermission dhtPermission;
    private final boolean collaborative;
    private final int maxCandidates;

    /** Compatibility constructor: DHT disabled unless explicitly configured. */
    public NativePublicPeerFinder(TrackerSource trackers,
                                  PeerDiscoveryAccelerator accelerator,
                                  TrackerAddressPolicy policy) {
        this(trackers,accelerator,policy,(node,hash)->List.of(),()->false);
    }

    public NativePublicPeerFinder(TrackerSource trackers,
                                  PeerDiscoveryAccelerator accelerator,
                                  TrackerAddressPolicy policy,
                                  DhtSource dht,DhtPermission dhtPermission) {
        this(trackers,(uris,h,id,p)->List.of(),accelerator,policy,dht,dhtPermission);
    }

    public NativePublicPeerFinder(TrackerSource trackers,OwnerTrackerSource ownerTrackers,
                                  PeerDiscoveryAccelerator accelerator,
                                  TrackerAddressPolicy policy,
                                  DhtSource dht,DhtPermission dhtPermission) {
        this(trackers,ownerTrackers,accelerator,policy,dht,dhtPermission,false);
    }

    public NativePublicPeerFinder(TrackerSource trackers,OwnerTrackerSource ownerTrackers,
                                  PeerDiscoveryAccelerator accelerator,
                                  TrackerAddressPolicy policy,
                                  DhtSource dht,DhtPermission dhtPermission,
                                  boolean collaborative) {
        this(trackers,ownerTrackers,accelerator,policy,dht,dhtPermission,collaborative,64);
    }

    public NativePublicPeerFinder(TrackerSource trackers,OwnerTrackerSource ownerTrackers,
                                  PeerDiscoveryAccelerator accelerator,
                                  TrackerAddressPolicy policy,
                                  DhtSource dht,DhtPermission dhtPermission,
                                  boolean collaborative,int maxCandidates) {
        if(trackers==null || ownerTrackers==null || accelerator==null || policy==null ||
                dht==null || dhtPermission==null || maxCandidates<1 || maxCandidates>256)
            throw new IllegalArgumentException("Missing or invalid public Peer discovery adapter");
        this.trackers=trackers;
        this.ownerTrackers=ownerTrackers;
        this.accelerator=accelerator;
        this.policy=policy;
        this.dht=dht;
        this.dhtPermission=dhtPermission;
        this.collaborative=collaborative;
        this.maxCandidates=maxCandidates;
    }

    @Override public List<InetSocketAddress> find(byte[] hash,byte[] peerId,int port,
                                                   VerifiedSwarmPrivacy.PublicPermit permit)
            throws IOException {
        return doFind(hash,peerId,port,permit,false);
    }

    /**
     * Thorough discovery for inter-round refresh: unlike the first-round fast path,
     * it skips the >=16 early-return and always consults DHT + PeX enrichment,
     * even when the tracker already returned enough candidates. This keeps the
     * pool fresh when tracker peers are mostly dead (common in public swarms).
     */
    public List<InetSocketAddress> findThorough(byte[] hash,byte[] peerId,int port,
                                                VerifiedSwarmPrivacy.PublicPermit permit)
            throws IOException {
        return doFind(hash,peerId,port,permit,true);
    }

    private List<InetSocketAddress> doFind(byte[] hash,byte[] peerId,int port,
                                           VerifiedSwarmPrivacy.PublicPermit permit,
                                           boolean thorough) throws IOException {
        if(hash==null || hash.length!=20 || peerId==null || peerId.length!=20
                || port<1 || port>65535)
            throw new IllegalArgumentException("Invalid public swarm discovery request");
        if(permit==null)throw new SecurityException("Verified public torrent required");
        permit.requireMatch(HexFormat.of().formatHex(hash));
        if(Thread.currentThread().isInterrupted())
            throw new IOException("Public Peer discovery interrupted");

        NativeNetworkBudget.check();
        var initial=new PeerCandidatePool(maxCandidates,policy);
        if(collaborative) {
            // Collaborative discovery: Task Trackers + Global Trackers + DHT + PeX + LSD all in synergy
            if(!permit.trackers().isEmpty()) {
                try {
                    for(var endpoint:shuffledCandidates(ownerTrackers.collect(
                            permit.trackers(),hash.clone(),peerId.clone(),port),
                            ByteBuffer.wrap(peerId,12,8).getLong())) {
                        if(Thread.currentThread().isInterrupted())
                            throw new IOException("Verified Tracker discovery interrupted");
                        initial.offer(endpoint,PeerCandidatePool.Source.PUBLIC_TRACKER);
                        if(initial.size()>=maxCandidates)break;
                    }
                }catch(IOException ignored){}
            }
            if(initial.size()<maxCandidates) try {
                List<InetSocketAddress> announced=trackers.collect(hash.clone(),peerId.clone(),port);
                var sampled=shuffledCandidates(announced,ByteBuffer.wrap(peerId,12,8).getLong());
                for(var endpoint:sampled) {
                    if(Thread.currentThread().isInterrupted())
                        throw new IOException("Public Tracker discovery interrupted");
                    initial.offer(endpoint,PeerCandidatePool.Source.PUBLIC_TRACKER);
                    if(initial.size()>=maxCandidates)break;
                }
            }catch(IOException ignored){}
            NativeNetworkBudget.check();
            // Discovery is not download: do not make an established swarm wait
            // for UDP DHT, eight PeX handshakes, and LSD before its first Piece.
            // A subsequent round may re-discover if these peers cannot transfer.
            // The thorough variant skips this early return so inter-round refresh
            // can replenish from DHT/PeX even when tracker returns >=16 peers.
            if(!thorough && initial.size()>=Math.min(16,maxCandidates))
                return initial.snapshot().stream().map(PeerCandidatePool.Candidate::address).toList();
            if(initial.size()<maxCandidates && dhtPermission.enabled()) {
                byte[] localId=new byte[20];
                RANDOM.nextBytes(localId);
                try {
                    for(var endpoint:shuffledCandidates(dht.collect(localId,hash.clone()),
                            ByteBuffer.wrap(localId).getLong())) {
                        if(Thread.currentThread().isInterrupted())
                            throw new IOException("DHT discovery interrupted");
                        initial.offer(endpoint,PeerCandidatePool.Source.DHT);
                        if(initial.size()>=maxCandidates)break;
                    }
                }catch(IOException ignored){}
            }
            NativeNetworkBudget.check();
            var lanNic=defaultLanInterface();
            var candidates=accelerator.enrich(initial.snapshot(),hash.clone(),
                    peerId.clone(),port,permit,
                    new PeerDiscoveryAccelerator.Options(true,true,lanNic!=null,true,lanNic));
            NativeNetworkBudget.check();
            return candidates.candidates().stream()
                    .map(PeerCandidatePool.Candidate::address).toList();
        }

        IOException trackerFailure=null;
        // First use task-private, vetted announce URLs from a user-uploaded .torrent.
        // This path never accepts an API-provided Tracker URL or a raw magnet tr=.
        if(!permit.trackers().isEmpty()) {
            try {
                for(var endpoint:shuffledCandidates(ownerTrackers.collect(
                        permit.trackers(),hash.clone(),peerId.clone(),port),
                        ByteBuffer.wrap(peerId,12,8).getLong())) {
                    if(Thread.currentThread().isInterrupted())
                        throw new IOException("Verified Tracker discovery interrupted");
                    initial.offer(endpoint,PeerCandidatePool.Source.PUBLIC_TRACKER);
                    if(initial.size()>=maxCandidates)break;
                }
            }catch(IOException e) {
                if(Thread.currentThread().isInterrupted())throw e;
                trackerFailure=e;
            }
        }
        if(initial.size()==0)try {
            List<InetSocketAddress> announced=trackers.collect(hash.clone(),peerId.clone(),port);
            // Tracker lists are commonly biased toward their first returned entries.
            // Shuffle a bounded prefix before reserving the configured discovery slots; the
            // caller's per-task Peer ID changes on each authorized Worker round.
            var sampled=shuffledCandidates(announced,ByteBuffer.wrap(peerId,12,8).getLong());
            for(var endpoint:sampled) {
                if(Thread.currentThread().isInterrupted())
                    throw new IOException("Public Tracker discovery interrupted");
                initial.offer(endpoint,PeerCandidatePool.Source.PUBLIC_TRACKER);
                if(initial.size()>=maxCandidates)break;
            }
        }catch(IOException e) {
            if(Thread.currentThread().isInterrupted())throw e;
            if(trackerFailure!=null)e.addSuppressed(trackerFailure);
            trackerFailure=e;
        }
        NativeNetworkBudget.check();
        if(initial.size()==0 && dhtPermission.enabled()) {
            byte[] localId=new byte[20];
            RANDOM.nextBytes(localId);
            try {
                for(var endpoint:shuffledCandidates(dht.collect(localId,hash.clone()),
                        ByteBuffer.wrap(localId).getLong())) {
                    if(Thread.currentThread().isInterrupted())
                        throw new IOException("DHT discovery interrupted");
                    initial.offer(endpoint,PeerCandidatePool.Source.DHT);
                    if(initial.size()>=maxCandidates)break;
                }
            }catch(IOException e) {
                if(Thread.currentThread().isInterrupted())throw e;
                // No fallback peers; the caller keeps its task READY.
            }
        }
        NativeNetworkBudget.check();
        if(initial.size()==0) {
            if(trackerFailure!=null && !dhtPermission.enabled())throw trackerFailure;
            return List.of();
        }

        var candidates=accelerator.enrich(initial.snapshot(),hash.clone(),
                peerId.clone(),port,permit,
                new PeerDiscoveryAccelerator.Options(true,true,false,false,null));
        NativeNetworkBudget.check();
        return candidates.candidates().stream()
                .map(PeerCandidatePool.Candidate::address).toList();
    }

    private static java.net.NetworkInterface defaultLanInterface() {
        try {
            var it = java.net.NetworkInterface.getNetworkInterfaces();
            while (it.hasMoreElements()) {
                var nic = it.nextElement();
                if (nic.isUp() && nic.supportsMulticast() && !nic.isLoopback() && !nic.isPointToPoint()) {
                    boolean hasPrivate = nic.inetAddresses()
                            .anyMatch(ip -> ip instanceof java.net.Inet4Address && ip.isSiteLocalAddress());
                    if (hasPrivate) return nic;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    static List<InetSocketAddress> shuffledCandidates(List<InetSocketAddress> supplied,long seed) {
        if(supplied==null)throw new IllegalArgumentException("Missing public Peer candidates");
        var pool=new ArrayList<>(supplied.subList(0,Math.min(2048,supplied.size())));
        Collections.shuffle(pool,new Random(seed));
        return pool;
    }
}
