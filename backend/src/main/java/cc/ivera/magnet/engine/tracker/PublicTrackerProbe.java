package cc.ivera.magnet.engine.tracker;

import java.io.IOException;
import java.net.URI;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import cc.ivera.magnet.engine.piece.NativeNetworkBudget;

/** Probe bounded candidates for a PUBLIC swarm. Alive means announce replied, not has peers. */
public final class PublicTrackerProbe {
    @FunctionalInterface
    public interface Announcer {
        TrackerProtocol.AnnounceResponse announce(URI uri, byte[] hash,byte[] peerId,int port)
                throws IOException;
    }
    public record TrackerStatus(URI tracker,boolean responding,int peerCount,long millis) {}
    public record Result(List<TrackerStatus> checked,List<InetSocketAddress> peers) {
        public Result { checked=List.copyOf(checked); peers=List.copyOf(peers); }
    }
    private final TrustedTrackerCatalog catalog;
    private final Announcer announcer;
    private final TrackerAddressPolicy policy;
    public PublicTrackerProbe(TrustedTrackerCatalog catalog,Announcer announcer,TrackerAddressPolicy policy) {
        if(catalog==null || announcer==null || policy==null)throw new IllegalArgumentException("Missing tracker probe dependency");
        this.catalog=catalog;this.announcer=announcer;this.policy=policy;
    }
    public static PublicTrackerProbe network(TrackerAddressPolicy policy) {
        var http=new HttpTrackerClient(policy);
        var udp=new UdpTrackerClient(policy);
        return new PublicTrackerProbe(TrustedTrackerCatalog.publicFeed(),
                (uri,hash,peerId,port) -> {
                    if("udp".equalsIgnoreCase(uri.getScheme()))
                        return udp.announce(uri,hash,peerId,0,1,0,port,
                                NativeNetworkBudget.cap(Duration.ofSeconds(3)));
                    return http.announce(uri,hash,peerId,0,1,0,port,
                            NativeNetworkBudget.cap(Duration.ofSeconds(4)));
                },policy);
    }
    public Result probe(byte[] infoHash,byte[] peerId,int port,
                        boolean publicSwarmOptIn,int maxTrackers) throws IOException {
        if(!publicSwarmOptIn) return new Result(List.of(),List.of());
        if(infoHash==null || infoHash.length!=20 || peerId==null || peerId.length!=20
                || port<1 || port>65535 || maxTrackers<1 || maxTrackers>64)
            throw new IllegalArgumentException("Invalid tracker probe");
        return probeUris(infoHash,peerId,port,catalog.candidates(true),maxTrackers);
    }

    /** Bounded subset of server-approved or torrent-supplied URI candidates. */
    public Result probeUris(byte[] infoHash,byte[] peerId,int port,
                            List<URI> candidates,int maxTrackers) throws IOException {
        if(infoHash==null || infoHash.length!=20 || peerId==null || peerId.length!=20
                || port<1 || port>65535 || candidates==null || maxTrackers<1 || maxTrackers>64)
            throw new IllegalArgumentException("Invalid explicit Tracker candidates");
        // Peer discovery is latency-sensitive: a blocked HTTPS Tracker must
        // never serialize 16 further Trackers or prevent the first Piece round.
        List<URI> valid=new ArrayList<>();
        for(URI uri:candidates) {
            if(valid.size()>=maxTrackers)break;
            if(uri==null || !List.of("http","https","udp").contains(
                    String.valueOf(uri.getScheme()).toLowerCase(java.util.Locale.ROOT))
                    || uri.getHost()==null || uri.getUserInfo()!=null || uri.getFragment()!=null
                    || uri.getPort()>65535 || ("udp".equalsIgnoreCase(uri.getScheme())
                    && uri.getPort()<1))continue;
            valid.add(uri);
        }
        if(valid.isEmpty())return new Result(List.of(),List.of());
        NativeNetworkBudget.check();
        record Checked(int index,URI uri,boolean responding,List<InetSocketAddress> peers,long elapsed) {}
        var workers=java.util.concurrent.Executors.newFixedThreadPool(Math.min(4,valid.size()),r->{
            Thread thread=new Thread(r,"clipfetch-tracker-probe");
            thread.setDaemon(true);
            return thread;
        });
        var completions=new java.util.concurrent.ExecutorCompletionService<Checked>(workers);
        var futures=new ArrayList<java.util.concurrent.Future<Checked>>();
        try {
            for(int index=0;index<valid.size();index++) {
                final int position=index;
                final URI tracker=valid.get(index);
                futures.add(completions.submit(()->{
                    long started=System.nanoTime();
                    try {
                        var response=announcer.announce(tracker,infoHash.clone(),peerId.clone(),port);
                        List<InetSocketAddress> accepted=new ArrayList<>();
                        for(var endpoint:response.peers()) {
                            try {
                                // Vet all untrusted tracker endpoints; never re-resolve
                                // the Tracker itself here (DNS rebinding).
                                policy.resolve(endpoint.getHostString());
                                if(!accepted.contains(endpoint) && accepted.size()<256)
                                    accepted.add(endpoint);
                            }catch(Exception ignored){}
                        }
                        return new Checked(position,tracker,true,accepted,
                                Duration.ofNanos(System.nanoTime()-started).toMillis());
                    }catch(IOException | RuntimeException failure) {
                        return new Checked(position,tracker,false,List.of(),
                                Duration.ofNanos(System.nanoTime()-started).toMillis());
                    }
                }));
            }
            long timeout=NativeNetworkBudget.cap(Duration.ofSeconds(12)).toNanos();
            long deadline=System.nanoTime()+timeout;
            var checked=new ArrayList<Checked>();
            List<InetSocketAddress> peers=new ArrayList<>();
            for(int i=0;i<valid.size();i++) {
                long remaining=deadline-System.nanoTime();
                if(remaining<=0)break;
                java.util.concurrent.Future<Checked> completed;
                try {
                    completed=completions.poll(remaining,java.util.concurrent.TimeUnit.NANOSECONDS);
                    if(completed==null)break;
                    var outcome=completed.get();
                    checked.add(outcome);
                    for(var peer:outcome.peers()) {
                        if(!peers.contains(peer) && peers.size()<256)peers.add(peer);
                    }
                    // As soon as there are enough candidates, start the download.
                    // Future rounds can discover more peers as needed.
                    if(peers.size()>=24)break;
                }catch(InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Public Tracker discovery interrupted",interrupted);
                }catch(java.util.concurrent.ExecutionException error) {
                    // Optional source failed; do not suppress successful Trackers.
                }
            }
            NativeNetworkBudget.check();
            checked.sort(java.util.Comparator.comparingInt(Checked::index));
            List<TrackerStatus> statuses=new ArrayList<>();
            for(var c:checked)statuses.add(new TrackerStatus(c.uri(),c.responding(),
                    c.peers().size(),c.elapsed()));
            return new Result(statuses,peers);
        }finally {
            for(var future:futures)future.cancel(true);
            workers.shutdownNow();
        }
    }
}
