package cc.ivera.magnet.engine.discovery;

import cc.ivera.magnet.engine.dht.DhtLookupResponse;
import cc.ivera.magnet.engine.dht.DhtBootstrapLookup;
import cc.ivera.magnet.engine.dht.IterativePeerLookup;
import cc.ivera.magnet.engine.magnet.MagnetUriParser;
import cc.ivera.magnet.engine.metainfo.VerifiedSwarmPrivacy;
import cc.ivera.magnet.engine.peer.PeerCandidatePool;
import cc.ivera.magnet.engine.tracker.HttpTrackerClient;
import cc.ivera.magnet.engine.tracker.PublicTrackerProbe;
import cc.ivera.magnet.engine.tracker.TrackerAddressPolicy;
import cc.ivera.magnet.engine.tracker.UdpTrackerClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

/** P2 discovery only: bounded candidates, no user task mutation, no downloads. */
public final class DiscoveryService {
    @FunctionalInterface
    public interface TrackerAnnouncer {
        List<InetSocketAddress> announce(URI tracker, byte[] infoHash,
                                         byte[] peerId, int listenPort) throws IOException;
    }

    @FunctionalInterface
    public interface DhtPeerFinder {
        List<InetSocketAddress> find(byte[] infoHash, List<DhtLookupResponse.Node> seeds) throws IOException;
    }

    @FunctionalInterface
    public interface PublicTrackerFinder {
        PublicTrackerProbe.Result find(byte[] infoHash,byte[] peerId,int listenPort) throws IOException;
    }

    public record DiscoveryResult(String infoHash,
                                  List<PeerCandidatePool.Candidate> candidates,
                                  int trackersQueried, int trackerFailures) {
        public DiscoveryResult { candidates = List.copyOf(candidates); }
    }

    private final MagnetUriParser parser = new MagnetUriParser();
    private final TrackerAddressPolicy addressPolicy;
    private final TrackerAnnouncer tracker;
    private final DhtPeerFinder dht;
    private final PublicTrackerFinder publicTrackers;
    private final int maxPeers;
    private final int maxTrackers;

    public DiscoveryService(TrackerAddressPolicy addressPolicy, TrackerAnnouncer tracker,
                            DhtPeerFinder dht, int maxPeers, int maxTrackers) {
        this(addressPolicy,tracker,dht,maxPeers,maxTrackers,
                (hash,id,port)->new PublicTrackerProbe.Result(List.of(),List.of()));
    }

    public DiscoveryService(TrackerAddressPolicy addressPolicy,TrackerAnnouncer tracker,
                            DhtPeerFinder dht,int maxPeers,int maxTrackers,
                            PublicTrackerFinder publicTrackers) {
        if (addressPolicy == null || tracker == null || dht == null || publicTrackers == null
                || maxPeers < 1 || maxPeers > 10000 || maxTrackers < 1 || maxTrackers > 32) {
            throw new IllegalArgumentException("Invalid discovery configuration");
        }
        this.addressPolicy = addressPolicy;
        this.tracker = tracker;
        this.dht = dht;
        this.publicTrackers = publicTrackers;
        this.maxPeers = maxPeers;
        this.maxTrackers = maxTrackers;
    }

    /** Default adapters use existing native Tracker and DHT clients, never qBittorrent. */
    public static DiscoveryService network(TrackerAddressPolicy policy) {
        HttpTrackerClient http = new HttpTrackerClient(policy);
        UdpTrackerClient udp = new UdpTrackerClient(policy);
        IterativePeerLookup lookup = IterativePeerLookup.network(policy);
        DhtBootstrapLookup bootstrapLookup = DhtBootstrapLookup.publicNetwork(policy);
        SecureRandom random = new SecureRandom();
        TrackerAnnouncer trackers = (uri, hash, peerId, listenPort) -> {
            if ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) {
                // left=1: metadata-only discovery is not a completed seed.
                return http.announce(uri, hash, peerId, 0, 1, 0, listenPort,
                        Duration.ofSeconds(5)).peers();
            }
            if ("udp".equalsIgnoreCase(uri.getScheme())) {
                return udp.announce(uri, hash, peerId, 0, 1, 0, listenPort).peers();
            }
            throw new IOException("This native tracker transport is not supported: " + uri.getScheme());
        };
        DhtPeerFinder dht = (infoHash, seeds) -> {
            byte[] localId = new byte[20];
            random.nextBytes(localId);
            if (seeds.isEmpty()) return bootstrapLookup.find(localId, infoHash).peers();
            return lookup.lookup(localId, infoHash, seeds).peers();
        };
        var publicTrackers=PublicTrackerProbe.network(policy);
        return new DiscoveryService(policy, trackers, dht, 256, 32,
                (hash,id,port)->publicTrackers.probe(hash,id,port,true,4));
    }

    public DiscoveryResult discover(String magnetUri, byte[] peerId, int listenPort,
                                    List<DhtLookupResponse.Node> bootstrap) {
        return discoverInternal(magnetUri,peerId,listenPort,bootstrap,true,null,false);
    }

    /**
     * Unknown-privacy metadata lookup after explicit per-submission public-discovery consent.
     * This is not a public swarm permit and must not be used for known-private metadata.
     * Only the fixed server Tracker catalog may supplement empty Tracker/DHT results.
     */
    public DiscoveryResult discoverWithPublicDiscoveryConsent(String magnetUri,byte[] peerId,
            int listenPort,List<DhtLookupResponse.Node> bootstrap) {
        return discoverInternal(magnetUri,peerId,listenPort,bootstrap,true,null,true);
    }

    /** Known private torrents may announce only to their explicit tracker(s). Never run DHT. */
    public DiscoveryResult discoverVerified(String magnetUri,byte[] peerId,int listenPort,
            List<DhtLookupResponse.Node> bootstrap,VerifiedSwarmPrivacy.Classification classification) {
        if(classification==null)throw new SecurityException("Validated metadata required");
        classification.requireMatch(parser.parse(magnetUri).infoHash());
        return discoverInternal(magnetUri,peerId,listenPort,bootstrap,
                !classification.privateTorrent(),classification.privateTorrent()?classification:null,false);
    }

    private DiscoveryResult discoverInternal(String magnetUri, byte[] peerId, int listenPort,
                                    List<DhtLookupResponse.Node> bootstrap,boolean allowDht,
                                    VerifiedSwarmPrivacy.Classification privateClassification,
                                    boolean allowPublicCatalog) {
        if (peerId == null || peerId.length != 20 || listenPort < 1 || listenPort > 65535
                || bootstrap == null || bootstrap.size() > 100) {
            throw new IllegalArgumentException("Invalid discovery request");
        }
        MagnetUriParser.MagnetInfo magnet = parser.parse(magnetUri);
        byte[] infoHash = HexFormat.of().parseHex(magnet.infoHash());
        PeerCandidatePool pool = new PeerCandidatePool(maxPeers, addressPolicy);
        int queries = 0, failures = 0;
        for (String trackerUrl : magnet.trackers()) {
            if (privateClassification!=null && !privateClassification.allowsTracker(trackerUrl))
                continue; // Untrusted magnet tr= must not override verified private .torrent trackers.
            if (queries >= maxTrackers || pool.size() >= maxPeers
                    || Thread.currentThread().isInterrupted()) break;
            queries++;
            URI uri = URI.create(trackerUrl);
            PeerCandidatePool.Source source =
                    "udp".equalsIgnoreCase(uri.getScheme())
                            ? PeerCandidatePool.Source.UDP_TRACKER
                            : PeerCandidatePool.Source.HTTP_TRACKER;
            try {
                for (InetSocketAddress address : tracker.announce(uri, infoHash, peerId.clone(), listenPort)) {
                    pool.offer(address, source);
                }
            } catch (IOException | RuntimeException failure) {
                failures++;
            }
        }
        // A Magnet without explicit Trackers must not block on a UDP DHT
        // bootstrap before trying the catalog the user explicitly consented to.
        // Hedge two independent discovery transports under a shared deadline.
        if(allowPublicCatalog && privateClassification==null &&
                pool.size()==0 && !Thread.currentThread().isInterrupted()) {
            var discoveryThreads=java.util.concurrent.Executors.newFixedThreadPool(2,r->{
                Thread thread=new Thread(r,"clipfetch-metadata-discovery");
                thread.setDaemon(true);
                return thread;
            });
            try {
                var pending=new java.util.ArrayList<java.util.concurrent.Callable<List<InetSocketAddress>>>();
                pending.add(()->{
                    try {return publicTrackers.find(infoHash.clone(),peerId.clone(),listenPort).peers();}
                    catch(IOException | RuntimeException failure){return List.of();}
                });
                if(allowDht)pending.add(()->{
                    try {return dht.find(infoHash.clone(),List.copyOf(bootstrap));}
                    catch(IOException | RuntimeException failure){return List.of();}
                });
                var finished=new java.util.concurrent.ExecutorCompletionService<
                        java.util.Map.Entry<Integer,List<InetSocketAddress>>>(discoveryThreads);
                var futures=new java.util.ArrayList<java.util.concurrent.Future<
                        java.util.Map.Entry<Integer,List<InetSocketAddress>>>>();
                for(int i=0;i<pending.size();i++) {
                    final int sourceIndex=i;
                    futures.add(finished.submit(()->java.util.Map.entry(sourceIndex,
                            pending.get(sourceIndex).call())));
                }
                try {
                    long cutoff=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
                    for(int handled=0;handled<pending.size();handled++) {
                        long remaining=cutoff-System.nanoTime();
                        if(remaining<=0)break;
                        var next=finished.poll(remaining,java.util.concurrent.TimeUnit.NANOSECONDS);
                        if(next==null)break;
                        try {
                            var response=next.get();
                            var source=response.getKey()==0?PeerCandidatePool.Source.PUBLIC_TRACKER:
                                    PeerCandidatePool.Source.DHT;
                            for(var address:response.getValue())pool.offer(address,source);
                            if(pool.size()>0)break; // use the first useful transport now
                        }catch(java.util.concurrent.ExecutionException failure){}
                    }
                }finally {
                    for(var future:futures)future.cancel(true);
                }
            }catch(InterruptedException cancelled) {
                Thread.currentThread().interrupt();
            }finally {
                discoveryThreads.shutdownNow();
            }
        }else {
            // Private torrent privacy and explicit Tracker behavior are unchanged.
            if (allowDht && (!bootstrap.isEmpty() || pool.size() == 0)
                    && pool.size() < maxPeers && !Thread.currentThread().isInterrupted()) {
                try {
                    for (InetSocketAddress address : dht.find(infoHash, List.copyOf(bootstrap)))
                        pool.offer(address,PeerCandidatePool.Source.DHT);
                } catch (IOException | RuntimeException ignored) {}
            }
        }
        return new DiscoveryResult(magnet.infoHash(), pool.snapshot(), queries, failures);
    }

    /**
     * Explicit public-swarm opt-in. Never invoked from normal discover().
     * Caller MUST determine swarm privacy from trusted validated metadata before enabling.
     */
    public DiscoveryResult discoverAccelerated(String magnetUri, byte[] peerId, int listenPort,
            List<DhtLookupResponse.Node> bootstrap, PeerDiscoveryAccelerator accelerator,
            VerifiedSwarmPrivacy.PublicPermit permit,
            PeerDiscoveryAccelerator.Options options) {
        if (accelerator == null || options == null)
            throw new IllegalArgumentException("Missing acceleration configuration");
        if(permit==null)throw new SecurityException("Verified public swarm permit required");
        String requestedHash=parser.parse(magnetUri).infoHash();
        permit.requireMatch(requestedHash); // No network traffic before permission matches.
        DiscoveryResult base = discover(magnetUri, peerId, listenPort, bootstrap);
        byte[] hash = HexFormat.of().parseHex(base.infoHash());
        var augmented = accelerator.enrich(base.candidates(), hash, peerId, listenPort, permit, options);
        return new DiscoveryResult(base.infoHash(), augmented.candidates(),
                base.trackersQueried(), base.trackerFailures());
    }
}
