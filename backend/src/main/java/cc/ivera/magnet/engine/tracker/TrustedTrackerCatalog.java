package cc.ivera.magnet.engine.tracker;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Opt-in public tracker catalog fetched ONLY from a fixed audited HTTPS URL.
 * Never accepts a user-controlled source URL, redirect, or non-public tracker transport.
 */
public final class TrustedTrackerCatalog {
    public static final URI SOURCE = URI.create(
            "https://raw.githubusercontent.com/ngosang/trackerslist/master/trackers_best.txt");
    private static final List<URI> MIRRORS = List.of(
            SOURCE,
            URI.create("https://ngosang.github.io/trackerslist/trackers_best.txt"),
            URI.create("https://cdn.jsdelivr.net/gh/ngosang/trackerslist@master/trackers_best.txt"));
    private static final int MAX_CATALOG_BYTES=16*1024;
    @FunctionalInterface
    public interface Fetcher { byte[] fetch() throws IOException; }

    private final Fetcher fetcher;
    private List<URI> cached=List.of();
    private long refreshAt;
    public TrustedTrackerCatalog(Fetcher fetcher) {
        if(fetcher==null)throw new IllegalArgumentException("Missing tracker feed");
        this.fetcher=fetcher;
    }
    public static TrustedTrackerCatalog publicFeed() {
        // A metadata-only request must not first wait for three slow/blocked
        // remote catalog mirrors. Seed with the locally audited tracker set;
        // refresh the live feed on later calls after the cache TTL.
        var result=new TrustedTrackerCatalog(() -> {
            try(HttpClient client=HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(4))
                    .followRedirects(HttpClient.Redirect.NEVER).build()) {
                IOException last=null;
                for(URI source:MIRRORS) {
                    try {
                        HttpRequest request=HttpRequest.newBuilder(source)
                                .timeout(Duration.ofSeconds(6)).GET().build();
                        HttpResponse<java.io.InputStream> response=client.send(request,
                                HttpResponse.BodyHandlers.ofInputStream());
                        try(var in=response.body()) {
                            if(response.statusCode()!=200)
                                throw new IOException("Tracker feed HTTP "+response.statusCode());
                            byte[] bytes=in.readNBytes(MAX_CATALOG_BYTES+1);
                            if(bytes.length>MAX_CATALOG_BYTES || bytes.length==0)
                                throw new IOException("Invalid public Tracker feed length");
                            if(parse(bytes).isEmpty())throw new IOException("No supported Tracker URLs");
                            return bytes;
                        }
                    }catch(IOException e){last=e;}
                }
                throw new IOException("Trusted public Tracker feeds unavailable",last);
            }catch(InterruptedException e){
                Thread.currentThread().interrupt();throw new IOException("Tracker feed interrupted",e);
            }
        });
        result.cached=defaultFallback();
        if(!result.cached.isEmpty())
            result.refreshAt=System.nanoTime()+Duration.ofMinutes(10).toNanos();
        return result;
    }

    public synchronized List<URI> candidates(boolean publicSwarmOptIn) throws IOException {
        if(!publicSwarmOptIn)return List.of();
        long now=System.nanoTime();
        if(now<refreshAt)return cached;
        try {
            List<URI> list=parse(fetcher.fetch());
            cached=list;
            refreshAt=System.nanoTime()+Duration.ofMinutes(30).toNanos();
            return list;
        } catch(IOException e) {
            // Do not erase a usable previously validated catalog on a transient fetch error.
            if(!cached.isEmpty())return cached;
            List<URI> fallback = defaultFallback();
            if(!fallback.isEmpty()) {
                cached = fallback;
                refreshAt = System.nanoTime() + Duration.ofMinutes(15).toNanos();
                return fallback;
            }
            throw e;
        }
    }

    public static List<URI> defaultFallback() {
        try {
            String text = String.join("\n", cc.ivera.magnet.domain.DefaultTrackers.TRACKERS);
            return parse(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            return List.of();
        }
    }
    public static List<URI> parse(byte[] input) throws IOException {
        if(input==null || input.length>MAX_CATALOG_BYTES)
            throw new IOException("Invalid tracker feed size");
        String text=new String(input,java.nio.charset.StandardCharsets.UTF_8);
        LinkedHashSet<URI> result=new LinkedHashSet<>();
        for(String line:text.split("\\R")) {
            String raw=line.trim();
            if(raw.isEmpty() || raw.startsWith("#") || raw.length()>300)continue;
            try {
                URI uri=URI.create(raw);
                String scheme=uri.getScheme();
                if(scheme==null || !(scheme.equalsIgnoreCase("udp") || scheme.equalsIgnoreCase("http")
                        || scheme.equalsIgnoreCase("https"))
                        || uri.getHost()==null || uri.getHost().isBlank()
                        || uri.getUserInfo()!=null || uri.getFragment()!=null
                        || (scheme.equalsIgnoreCase("udp") && uri.getPort()<1)
                        || uri.getPort()>65535)continue;
                result.add(uri);
                if(result.size()>=20)break;
            }catch(IllegalArgumentException ignored){}
        }
        // Avoid spending an entire round on UDP-only Trackers when UDP is filtered.
        return result.stream().sorted(java.util.Comparator.comparingInt(uri ->
                uri.getScheme().equalsIgnoreCase("https") ? 0 :
                uri.getScheme().equalsIgnoreCase("http") ? 1 : 2)).toList();
    }
}
