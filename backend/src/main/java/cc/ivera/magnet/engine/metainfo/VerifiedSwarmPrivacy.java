package cc.ivera.magnet.engine.metainfo;

import cc.ivera.magnet.engine.codec.BencodeDecoder;
import cc.ivera.magnet.engine.codec.BencodeDecoder.*;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Validate the EXACT v1 info hash before classifying swarm privacy.
 * A bare Magnet/InfoHash is NOT proof of public or private metadata.
 */
public final class VerifiedSwarmPrivacy {
    private VerifiedSwarmPrivacy() {}

    /** Can only be created from a parsed, hash-verified non-private info dictionary. */
    public static final class PublicPermit {
        private final String infoHash;
        private final List<URI> trackers;
        private PublicPermit(String hash,List<URI> trackers) {
            this.infoHash=hash;
            this.trackers=List.copyOf(trackers);
        }
        public String infoHash() { return infoHash; }
        public List<URI> trackers() { return trackers; }
        public void requireMatch(String hash) {
            if(!infoHash.equalsIgnoreCase(Objects.requireNonNull(hash,"InfoHash")))
                throw new SecurityException("Public discovery permission belongs to another InfoHash");
        }
    }

    public static final class Classification {
        private final String infoHash;
        private final boolean privateTorrent;
        private final Set<String> privateTrackerAllowlist;

        private Classification(String infoHash, boolean isPrivate, Set<String> trackers) {
            this.infoHash=Objects.requireNonNull(infoHash);
            this.privateTorrent=isPrivate;
            this.privateTrackerAllowlist=java.util.Collections.unmodifiableSet(
                    new LinkedHashSet<>(trackers));
        }
        public String infoHash() { return infoHash; }
        public boolean privateTorrent() { return privateTorrent; }
        public Set<String> privateTrackerAllowlist() { return privateTrackerAllowlist; }
        /** Task-local public trackers; never place these in shared infoHash metadata. */
        public List<String> publicTrackerUrls() {
            return privateTorrent ? List.of() : List.copyOf(privateTrackerAllowlist);
        }
        public void requireMatch(String hash) {
            if(!infoHash.equalsIgnoreCase(Objects.requireNonNull(hash,"InfoHash")))
                throw new SecurityException("Validated metainfo belongs to another InfoHash");
        }
        public boolean allowsTracker(String uri) {
            return !privateTorrent || privateTrackerAllowlist.contains(uri);
        }
        public PublicPermit requirePublicPermit() {
            if(privateTorrent)throw new SecurityException("Private torrent prohibits open peer discovery");
            return new PublicPermit(infoHash,List.of());
        }
        /** Restores only task-owned URL candidates; callers must load by owner/task. */
        public PublicPermit requirePublicPermit(List<String> taskTrackers) {
            if(privateTorrent)throw new SecurityException("Private torrent prohibits open peer discovery");
            if(taskTrackers==null || taskTrackers.size()>64)
                throw new SecurityException("Invalid task-local Tracker list");
            var unique=new LinkedHashSet<URI>();
            for(String raw:taskTrackers) {
                if(raw==null || raw.length()>512)
                    throw new SecurityException("Invalid task Tracker URL");
                URI uri;
                try {uri=URI.create(raw);}
                catch(IllegalArgumentException e){throw new SecurityException("Malformed task Tracker URL",e);}
                String scheme=uri.getScheme();
                if(scheme==null || !(scheme.equalsIgnoreCase("http") ||
                        scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("udp"))
                        || uri.getHost()==null || uri.getUserInfo()!=null || uri.getFragment()!=null
                        || uri.getPort()>65535 || (scheme.equalsIgnoreCase("udp") && uri.getPort()<1))
                    throw new SecurityException("Unsupported task Tracker URL");
                unique.add(uri);
            }
            return new PublicPermit(infoHash,List.copyOf(unique));
        }
    }

    public static Classification verifyTorrent(byte[] bytes,String requestedHash) {
        if(bytes==null || requestedHash==null || !requestedHash.matches("(?i)[0-9a-f]{40}"))
            throw new IllegalArgumentException("Missing v1 torrent InfoHash");
        var document=new BencodeDecoder().decodeTorrent(bytes);
        var parsed=new TorrentV1MetainfoParser().parse(bytes);
        if(!parsed.infoHash().equalsIgnoreCase(requestedHash))
            throw new SecurityException("Torrent InfoHash mismatch");
        DictionaryValue info=(DictionaryValue)document.root().required("info");
        Value raw=info.get("private");
        boolean isPrivate=false;
        if(raw!=null) {
            if(!(raw instanceof IntegerValue number) || (number.value()!=0 && number.value()!=1))
                throw new IllegalArgumentException("Invalid private flag");
            isPrivate=((IntegerValue)raw).value()==1;
        }
        Set<String> allowed=parseAuthorizedTrackers(document.root());
        return new Classification(parsed.infoHash(),isPrivate,allowed);
    }

    /** BEP9 gives only raw info bytes and therefore cannot authenticate any tracker list. */
    public static Classification verifyRawInfo(byte[] raw,String requestedHash) {
        if(raw==null || raw.length<1 || raw.length>BencodeDecoder.MAX_METADATA_BYTES-8)
            throw new IllegalArgumentException("Invalid raw torrent info size");
        byte[] result=new byte[raw.length+8];
        System.arraycopy("d4:info".getBytes(StandardCharsets.US_ASCII),0,result,0,7);
        System.arraycopy(raw,0,result,7,raw.length);
        result[result.length-1]='e';
        return verifyTorrent(result,requestedHash);
    }

    private static Set<String> parseAuthorizedTrackers(DictionaryValue root) {
        LinkedHashSet<String> trackers=new LinkedHashSet<>();
        if(root.get("announce")!=null)addTracker(root.get("announce"),trackers);
        Value tiers=root.get("announce-list");
        if(tiers!=null) {
            if(!(tiers instanceof ListValue list))
                throw new IllegalArgumentException("Malformed private tracker list");
            for(Value tier:list.values()) {
                if(!(tier instanceof ListValue group))
                    throw new IllegalArgumentException("Malformed tracker tier");
                for(Value tracker:group.values())addTracker(tracker,trackers);
            }
        }
        return java.util.Collections.unmodifiableSet(trackers);
    }
    private static void addTracker(Value value,Set<String> out) {
        if(!(value instanceof BytesValue item) || item.bytes().length>2048)
            throw new IllegalArgumentException("Invalid private tracker URL");
        String url=new String(item.bytes(),StandardCharsets.UTF_8);
        URI uri;
        try {uri=URI.create(url);}
        catch(IllegalArgumentException e){throw new IllegalArgumentException("Malformed private tracker URI",e);}
        String scheme=uri.getScheme();
        if(scheme==null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https")
                || scheme.equalsIgnoreCase("udp")) || uri.getHost()==null ||
                uri.getUserInfo()!=null || uri.getFragment()!=null)
            throw new IllegalArgumentException("Unsupported private tracker URI");
        if(out.size()>=64)throw new IllegalArgumentException("Too many trackers");
        out.add(uri.toString());
    }
}
