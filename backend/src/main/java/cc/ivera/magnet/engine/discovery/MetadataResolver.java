package cc.ivera.magnet.engine.discovery;

import cc.ivera.magnet.engine.dht.DhtLookupResponse;
import cc.ivera.magnet.engine.metadata.PeerMetadataSession;
import cc.ivera.magnet.engine.metainfo.TorrentV1MetainfoParser;
import cc.ivera.magnet.engine.metainfo.VerifiedSwarmPrivacy;
import cc.ivera.magnet.engine.peer.PeerCandidatePool;
import cc.ivera.magnet.engine.tracker.TrackerAddressPolicy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/** Magnet -> discovered peers -> verified original info dict -> v1 file list. */
public final class MetadataResolver {
    @FunctionalInterface
    public interface PeerMetadataFetcher {
        byte[] fetch(InetSocketAddress endpoint, byte[] infoHash, byte[] peerId,
                     Duration timeout) throws IOException;
    }

    public record Resolution(TorrentV1MetainfoParser.TorrentInfo torrent,
                             int discoveredPeers, int attemptedPeers,byte[] verifiedTorrentBytes) {
        public Resolution(TorrentV1MetainfoParser.TorrentInfo torrent,int discoveredPeers,int attemptedPeers) {
            this(torrent,discoveredPeers,attemptedPeers,null);
        }
        public Resolution { if(verifiedTorrentBytes!=null)verifiedTorrentBytes=verifiedTorrentBytes.clone(); }
        @Override public byte[] verifiedTorrentBytes() {
            return verifiedTorrentBytes==null?null:verifiedTorrentBytes.clone();
        }
    }

    private final DiscoveryService discovery;
    private final PeerMetadataFetcher fetcher;
    private final int maxPeerAttempts;
    private final Duration peerTimeout;
    private final TorrentV1MetainfoParser torrentParser = new TorrentV1MetainfoParser();

    public MetadataResolver(DiscoveryService discovery, PeerMetadataFetcher fetcher,
                            int maxPeerAttempts, Duration peerTimeout) {
        if (discovery == null || fetcher == null || maxPeerAttempts < 1
                || maxPeerAttempts > 100 || peerTimeout == null || peerTimeout.isZero()
                || peerTimeout.isNegative() || peerTimeout.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("Invalid resolver configuration");
        }
        this.discovery = discovery;
        this.fetcher = fetcher;
        this.maxPeerAttempts = maxPeerAttempts;
        this.peerTimeout = peerTimeout;
    }

    public static MetadataResolver network(TrackerAddressPolicy policy) {
        PeerMetadataSession session = new PeerMetadataSession(policy);
        return new MetadataResolver(DiscoveryService.network(policy),
                session::fetch, 8, Duration.ofSeconds(6));
    }

    /** Public-swarm tuning: larger swarms often need more than the conservative 8×6s tries. */
    public static MetadataResolver network(TrackerAddressPolicy policy,
                                          int maxPeerAttempts, Duration peerTimeout) {
        PeerMetadataSession session = new PeerMetadataSession(policy);
        return new MetadataResolver(DiscoveryService.network(policy),
                session::fetch, maxPeerAttempts, peerTimeout);
    }

    public Resolution resolve(String magnetUri, byte[] peerId, int listenPort,
                              List<DhtLookupResponse.Node> bootstrap) throws IOException {
        return resolveCandidates(discovery.discover(magnetUri, peerId, listenPort, bootstrap), peerId);
    }

    /** Per-submission consent allows bounded fixed-catalog fallback before privacy is known. */
    public Resolution resolveWithPublicDiscoveryConsent(String magnetUri,byte[] peerId,int listenPort,
            List<DhtLookupResponse.Node> bootstrap) throws IOException {
        return resolveCandidates(discovery.discoverWithPublicDiscoveryConsent(
                magnetUri,peerId,listenPort,bootstrap),peerId);
    }

    /**
     * Explicit opt-in ONLY for already verified public torrents; normal resolve()
     * never invokes PeX, LSD or public Tracker catalogs.
     */
    public Resolution resolvePublicAccelerated(String magnetUri, byte[] peerId, int listenPort,
            List<DhtLookupResponse.Node> bootstrap, PeerDiscoveryAccelerator accelerator,
            VerifiedSwarmPrivacy.PublicPermit permit,
            PeerDiscoveryAccelerator.Options options) throws IOException {
        if (permit == null || options == null || !options.verifiedPublicSwarm())
            throw new SecurityException("Validated public swarm permit required before acceleration");
        return resolveCandidates(discovery.discoverAccelerated(
                magnetUri, peerId, listenPort, bootstrap, accelerator, permit, options), peerId);
    }

    /** Known-private metadata must only query its explicitly permitted trackers (never DHT). */
    public Resolution resolveVerified(String magnetUri, byte[] peerId, int listenPort,
            List<DhtLookupResponse.Node> bootstrap, VerifiedSwarmPrivacy.Classification classification)
            throws IOException {
        return resolveCandidates(discovery.discoverVerified(
                magnetUri, peerId, listenPort, bootstrap, classification), peerId);
    }

    /** Prefer distinct IPv4 /24 prefixes before trying another host from the
     * same prefix. Tracker replies often contain many unreachable sister IPs. */
    static List<PeerCandidatePool.Candidate> diversePeers(List<PeerCandidatePool.Candidate> peers) {
        var groups=new java.util.LinkedHashMap<String,java.util.ArrayDeque<PeerCandidatePool.Candidate>>();
        for(var peer:peers) {
            String host=peer.address().getHostString().toLowerCase(java.util.Locale.ROOT);
            String[] octets=host.split("\\.");
            String key=host;
            if(octets.length==4) {
                try {
                    boolean ipv4=true;
                    for(String octet:octets) {
                        int value=Integer.parseInt(octet);
                        if(value<0 || value>255)ipv4=false;
                    }
                    if(ipv4)key=octets[0]+"."+octets[1]+"."+octets[2];
                }catch(NumberFormatException ignored){}
            }
            groups.computeIfAbsent(key,k->new java.util.ArrayDeque<>()).add(peer);
        }
        var ordered=new java.util.ArrayList<PeerCandidatePool.Candidate>(peers.size());
        boolean pending;
        do {
            pending=false;
            for(var group:groups.values()) {
                if(!group.isEmpty()) {ordered.add(group.removeFirst());pending=true;}
            }
        }while(pending);
        return List.copyOf(ordered);
    }

    private Resolution resolveCandidates(DiscoveryService.DiscoveryResult results,
                                         byte[] peerId) throws IOException {
        byte[] expected=java.util.HexFormat.of().parseHex(results.infoHash());
        var candidates=diversePeers(results.candidates());
        int planned=Math.min(maxPeerAttempts,candidates.size());
        if(planned==0)throw new IOException(
                "No reachable Peer discovered via Tracker/DHT; please retry later or upload a .torrent");
        // One unresponsive BEP9 Peer must not spend the entire metadata job budget.
        // Limit both worker threads and submissions; first SHA-1-verified result wins.
        int workers=Math.min(4,planned);
        var executor=java.util.concurrent.Executors.newFixedThreadPool(workers,r->{
            var thread=new Thread(r,"clipfetch-metadata-peer");
            thread.setDaemon(true);
            return thread;
        });
        var completed=new java.util.concurrent.ExecutorCompletionService<Resolution>(executor);
        var futures=new java.util.ArrayList<java.util.concurrent.Future<Resolution>>(planned);
        int failures=0;
        IOException lastFailure=null;
        long started=System.nanoTime();
        try {
            for(int i=0;i<planned;i++) {
                final int selected=i;
                futures.add(completed.submit(()->{
                    var candidate=candidates.get(selected);
                    byte[] rawInfo=fetcher.fetch(candidate.address(),expected.clone(),
                            peerId.clone(),peerTimeout);
                    if(rawInfo==null || rawInfo.length==0)
                        throw new IOException("Peer returned empty metadata");
                    ByteArrayOutputStream encoded=new ByteArrayOutputStream();
                    // Preserve original raw info bytes to verify v1 InfoHash exactly.
                    encoded.writeBytes("d4:info".getBytes(StandardCharsets.US_ASCII));
                    encoded.writeBytes(rawInfo);
                    encoded.write('e');
                    var torrent=new TorrentV1MetainfoParser().parse(encoded.toByteArray());
                    if(!torrent.infoHash().equals(results.infoHash()))
                        throw new IOException("Peer returned a mismatching InfoHash");
                    return new Resolution(torrent,results.candidates().size(),
                            selected+1,encoded.toByteArray());
                }));
            }
            // Three waves of <=8s peers should fit comfortably inside the
            // 90s metadata job including independent Tracker/DHT discovery.
            long cutoff=started+java.util.concurrent.TimeUnit.SECONDS.toNanos(55);
            for(int done=0;done<planned;done++) {
                if(Thread.currentThread().isInterrupted())
                    throw new IOException("Metadata discovery cancelled");
                long remaining=cutoff-System.nanoTime();
                if(remaining<=0)break;
                java.util.concurrent.Future<Resolution> next;
                try {
                    next=completed.poll(remaining,java.util.concurrent.TimeUnit.NANOSECONDS);
                    if(next==null)break;
                    try {
                        Resolution result=next.get();
                        org.slf4j.LoggerFactory.getLogger(MetadataResolver.class).info(
                                "native_metadata peersDiscovered={} peersSubmitted={} peerResponsesFailed={} fetchWallMs={}",
                                results.candidates().size(),planned,failures,
                                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started));
                        return new Resolution(result.torrent(),results.candidates().size(),
                                planned,result.verifiedTorrentBytes());
                    }catch(java.util.concurrent.ExecutionException failure) {
                        failures++;
                        lastFailure=new IOException("Peer returned no valid v1 metadata",
                                failure.getCause());
                    }
                }catch(InterruptedException cancelled) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Metadata discovery interrupted",cancelled);
                }
            }
        }finally {
            for(var future:futures)future.cancel(true);
            executor.shutdownNow();
        }
        throw new IOException("No Peer supplied verified v1 metadata (discovered "+
                results.candidates().size()+", attempted up to "+planned+
                ", failed "+failures+"); retry or upload a .torrent",lastFailure);
    }
}
