package cc.ivera.magnet.engine.piece;

import cc.ivera.magnet.engine.peer.PeerCandidatePool;
import cc.ivera.magnet.engine.tracker.TrackerAddressPolicy;

import java.io.IOException;
import java.net.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * Public Ubuntu acceptance diagnostic. Tests ONLY addresses returned by the
 * public Tracker/DHT, pins them with the same outbound policy as the engine,
 * and never performs a general port scan or changes OS networking.
 *
 * Sampling is reproducibly shuffled instead of taking the first N responses.
 */
final class PublicPeerReachabilitySampler {
    interface TcpConnector {
        void connect(InetSocketAddress pinned,int timeoutMillis) throws IOException;
    }
    enum Outcome { REACHABLE, REFUSED, TIMEOUT, UNREACHABLE, OTHER }
    record Summary(int candidates,int sampled,int reachable,int refused,
                   int timedOut,int unreachable,int other,
                   int distinctPorts,int commonBtPorts) {}
    record Result(List<InetSocketAddress> reachable,Summary summary) {
        Result {reachable=List.copyOf(reachable);}
    }
    private record Attempt(InetSocketAddress pinned,Outcome outcome) {}

    static Result sample(List<PeerCandidatePool.Candidate> candidates,
                         TrackerAddressPolicy policy,int maxSamples,
                         int parallelism,int connectMillis,Duration totalBudget,
                         long shuffleSeed) throws InterruptedException {
        return sample(candidates,policy,maxSamples,parallelism,connectMillis,totalBudget,
                shuffleSeed,(target,timeout)->{
                    try(Socket socket=new Socket()) {
                        socket.connect(target,timeout);
                    }
                });
    }

    static Result sample(List<PeerCandidatePool.Candidate> candidates,
                         TrackerAddressPolicy policy,int maxSamples,
                         int parallelism,int connectMillis,Duration totalBudget,
                         long shuffleSeed,TcpConnector connector) throws InterruptedException {
        if(candidates==null || policy==null || connector==null ||
                maxSamples<1 || maxSamples>256 || parallelism<1 || parallelism>12 ||
                connectMillis<50 || connectMillis>3000 || totalBudget==null ||
                totalBudget.isZero() || totalBudget.isNegative() ||
                totalBudget.compareTo(Duration.ofSeconds(30))>0)
            throw new IllegalArgumentException("Invalid public Peer sampling budget");
        List<PeerCandidatePool.Candidate> shuffled=new ArrayList<>(candidates);
        Collections.shuffle(shuffled,new Random(shuffleSeed));
        if(shuffled.size()>maxSamples)
            shuffled=new ArrayList<>(shuffled.subList(0,maxSamples));
        Set<Integer> ports=new HashSet<>();
        int common=0;
        for(var item:shuffled) {
            int port=item.address().getPort();
            ports.add(port);
            if(port==6881 || port==51413 || port==16881)common++;
        }

        ExecutorService workers=Executors.newFixedThreadPool(parallelism,r->{
            Thread thread=new Thread(r,"native-public-peer-connect-probe");
            thread.setDaemon(true);
            return thread;
        });
        List<InetSocketAddress> reachable=new ArrayList<>();
        EnumMap<Outcome,Integer> outcomes=new EnumMap<>(Outcome.class);
        for(Outcome o:Outcome.values())outcomes.put(o,0);
        try {
            List<Callable<Attempt>> calls=new ArrayList<>();
            for(var candidate:shuffled)calls.add(()->{
                InetSocketAddress peer=candidate.address();
                InetSocketAddress pinned;
                try {
                    // The original tracker/DHT Peer endpoint is UNTRUSTED.
                    // Restrict and pin its IP before attempting TCP.
                    pinned=new InetSocketAddress(policy.resolve(peer.getHostString()),
                            peer.getPort());
                    int perPeer=connectMillis;
                    try {
                        perPeer=(int)Math.max(1,NativeNetworkBudget.cap(
                                Duration.ofMillis(connectMillis)).toMillis());
                    }catch(IOException deadlineExceeded) {
                        return new Attempt(null,Outcome.TIMEOUT);
                    }
                    connector.connect(pinned,perPeer);
                    return new Attempt(pinned,Outcome.REACHABLE);
                }catch(ConnectException e) {
                    return new Attempt(null,Outcome.REFUSED);
                }catch(SocketTimeoutException e) {
                    return new Attempt(null,Outcome.TIMEOUT);
                }catch(NoRouteToHostException e) {
                    return new Attempt(null,Outcome.UNREACHABLE);
                }catch(IOException | SecurityException | IllegalArgumentException e) {
                    return new Attempt(null,Outcome.OTHER);
                }
            });
            List<Future<Attempt>> futures=workers.invokeAll(
                    calls,totalBudget.toMillis(),TimeUnit.MILLISECONDS);
            for(Future<Attempt> future:futures) {
                if(future.isCancelled()) {
                    outcomes.merge(Outcome.TIMEOUT,1,Integer::sum);
                    continue;
                }
                try {
                    Attempt response=future.get();
                    outcomes.merge(response.outcome(),1,Integer::sum);
                    if(response.pinned()!=null)
                        reachable.add(response.pinned());
                }catch(ExecutionException ignored) {
                    outcomes.merge(Outcome.OTHER,1,Integer::sum);
                }
            }
        }finally {
            workers.shutdownNow();
        }
        var summary=new Summary(candidates.size(),shuffled.size(),
                outcomes.get(Outcome.REACHABLE),outcomes.get(Outcome.REFUSED),
                outcomes.get(Outcome.TIMEOUT),outcomes.get(Outcome.UNREACHABLE),
                outcomes.get(Outcome.OTHER),ports.size(),common);
        return new Result(reachable,summary);
    }
}
