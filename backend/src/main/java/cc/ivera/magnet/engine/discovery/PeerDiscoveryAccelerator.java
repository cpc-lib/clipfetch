package cc.ivera.magnet.engine.discovery;

import cc.ivera.magnet.engine.lsd.LsdPeerDiscovery;
import cc.ivera.magnet.engine.metainfo.VerifiedSwarmPrivacy;
import cc.ivera.magnet.engine.pex.PeerExchangeSession;
import cc.ivera.magnet.engine.peer.PeerCandidatePool;
import cc.ivera.magnet.engine.tracker.PublicTrackerProbe;
import cc.ivera.magnet.engine.tracker.TrackerAddressPolicy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.time.Duration;
import java.util.List;
import cc.ivera.magnet.engine.piece.NativeNetworkBudget;

/**
 * Explicit opt-in, PUBLIC swarm only peer-discovery enrichment.
 * Does not create tasks, write to DB, or enable background discovery.
 */
public final class PeerDiscoveryAccelerator {
    @FunctionalInterface public interface PexCollector {
        List<InetSocketAddress> collect(InetSocketAddress knownPeer,byte[] infoHash,
                                        byte[] localPeerId) throws IOException;
    }
    @FunctionalInterface public interface LanCollector {
        List<InetSocketAddress> collect(byte[] infoHash,int listenPort,
                                        NetworkInterface networkInterface) throws IOException;
    }
    @FunctionalInterface public interface TrackerCollector {
        List<InetSocketAddress> collect(byte[] infoHash,byte[] localPeerId,
                                        int listenPort) throws IOException;
    }
    public record Options(boolean verifiedPublicSwarm, boolean enablePex,
                          boolean enableLsd,boolean enablePublicTrackerCatalog,
                          NetworkInterface lanInterface) {}
    public record Result(List<PeerCandidatePool.Candidate> candidates,
                         int pexConnections,int lanPeers,int trackerPeers) {
        public Result { candidates=List.copyOf(candidates); }
    }

    private final TrackerAddressPolicy policy;
    private final PexCollector pex;
    private final LanCollector lsd;
    private final TrackerCollector tracker;
    public PeerDiscoveryAccelerator(TrackerAddressPolicy policy,PexCollector pex,
                                    LanCollector lsd,TrackerCollector tracker) {
        if(policy==null || pex==null || lsd==null || tracker==null)
            throw new IllegalArgumentException("Missing acceleration provider");
        this.policy=policy;this.pex=pex;this.lsd=lsd;this.tracker=tracker;
    }
    public static PeerDiscoveryAccelerator network(TrackerAddressPolicy policy) {
        var pexSession=new PeerExchangeSession(policy);
        var lsdService=new LsdPeerDiscovery();
        var trackerService=PublicTrackerProbe.network(policy);
        return new PeerDiscoveryAccelerator(policy,
                (endpoint,hash,localPeerId)->pexSession.collect(
                        endpoint,hash,localPeerId,
                        NativeNetworkBudget.cap(Duration.ofSeconds(3)),true).added(),
                (hash,port,nic)->lsdService.discover(hash,port,nic,Duration.ofSeconds(2),true),
                (hash,localPeerId,port)->trackerService.probe(
                        hash,localPeerId,port,true,4).peers());
    }
    public Result enrich(List<PeerCandidatePool.Candidate> existing,byte[] hash,
                         byte[] peerId,int listenPort,
                         VerifiedSwarmPrivacy.PublicPermit publicPermit,Options options) {
        if(existing==null || options==null || hash==null || hash.length!=20
                || peerId==null || peerId.length!=20 || listenPort<1 || listenPort>65535)
            throw new IllegalArgumentException("Invalid acceleration parameters");
        if(publicPermit==null)throw new SecurityException("Verified public torrent metadata is required");
        publicPermit.requireMatch(java.util.HexFormat.of().formatHex(hash));
        PeerCandidatePool pool=new PeerCandidatePool(256,policy);
        for(var candidate:existing) {
            for(var source:candidate.sources()) {
                if(source==PeerCandidatePool.Source.LSD)pool.offerLan(candidate.address());
                else pool.offer(candidate.address(),source);
            }
        }
        if(!options.verifiedPublicSwarm())return new Result(pool.snapshot(),0,0,0);
        int tried=0, lanCount=0, trackerCount=0;
        if(options.enablePex() && !NativeNetworkBudget.exhausted()) {
            // PEX requires a live, handshaken Peer from Tracker/DHT; no Peers -> no PEX.
            for(var candidate:List.copyOf(pool.snapshot())) {
                if(tried>=8 || Thread.currentThread().isInterrupted() ||
                        NativeNetworkBudget.exhausted())break;
                if(candidate.sources().size()==1 &&
                        candidate.sources().contains(PeerCandidatePool.Source.LSD))continue;
                tried++;
                try {
                    for(var peer:pex.collect(candidate.address(),hash.clone(),peerId.clone()))
                        pool.offer(peer,PeerCandidatePool.Source.PEX);
                }catch(IOException | RuntimeException ignored){}
            }
        }
        if(options.enablePublicTrackerCatalog() && !Thread.currentThread().isInterrupted()) {
            try{
                for(var peer:tracker.collect(hash.clone(),peerId.clone(),listenPort))
                    if(pool.offer(peer,PeerCandidatePool.Source.PUBLIC_TRACKER))trackerCount++;
            }catch(IOException | RuntimeException ignored){}
        }
        if(options.enableLsd() && options.lanInterface()!=null && !Thread.currentThread().isInterrupted()) {
            try {
                for(var peer:lsd.collect(hash.clone(),listenPort,options.lanInterface()))
                    if(pool.offerLan(peer))lanCount++;
            }catch(IOException | RuntimeException ignored){}
        }
        return new Result(pool.snapshot(),tried,lanCount,trackerCount);
    }
}
