package cc.ivera.magnet.engine.metainfo;

import cc.ivera.magnet.engine.codec.BencodeDecoder.*;
import cc.ivera.magnet.engine.codec.BencodeEncoder;
import cc.ivera.magnet.engine.codec.ByteString;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class VerifiedSwarmPrivacyTest {
    static byte[] info(Long privateFlag) {
        Map<ByteString,Value> fields=new LinkedHashMap<>();
        fields.put(ByteString.ascii("length"),new IntegerValue(4));
        fields.put(ByteString.ascii("name"),new BytesValue("test".getBytes(StandardCharsets.US_ASCII)));
        fields.put(ByteString.ascii("piece length"),new IntegerValue(4));
        fields.put(ByteString.ascii("pieces"),new BytesValue(new byte[20]));
        if(privateFlag!=null)fields.put(ByteString.ascii("private"),new IntegerValue(privateFlag));
        return new BencodeEncoder().encode(new DictionaryValue(fields));
    }
    static String hash(byte[] info) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(info));
    }
    static byte[] fullTorrent(byte[] info) {
        var out=new ByteArrayOutputStream();
        out.writeBytes("d4:info".getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(info);
        out.write('e');
        return out.toByteArray();
    }

    @Test void authenticPublicTokenRequiresCompleteVerifiedInfo() throws Exception {
        byte[] raw=info(null);
        String hash=hash(raw);
        var cls=VerifiedSwarmPrivacy.verifyTorrent(fullTorrent(raw),hash);
        assertFalse(cls.privateTorrent());
        assertEquals(hash,cls.requirePublicPermit().infoHash());
        cls.requirePublicPermit().requireMatch(hash.toUpperCase());
        assertThrows(SecurityException.class,()->cls.requirePublicPermit().requireMatch("0".repeat(40)));
        assertFalse(VerifiedSwarmPrivacy.verifyRawInfo(raw,hash).privateTorrent());
    }
    @Test void privateEqualsOneMayNeverMintPublicPermit() throws Exception {
        byte[] raw=info(1L);
        var cls=VerifiedSwarmPrivacy.verifyRawInfo(raw,hash(raw));
        assertTrue(cls.privateTorrent());
        assertThrows(SecurityException.class,cls::requirePublicPermit);
    }
    @Test void publicTorrentKeepsTaskLocalAnnounceWithoutTrustingRawInfoTrackers()
            throws Exception {
        byte[] raw=info(null);
        String tracker="https://torrent.ubuntu.com/announce";
        var out=new ByteArrayOutputStream();
        out.writeBytes(("d8:announce"+tracker.length()+":"+tracker+"4:info")
                .getBytes(StandardCharsets.UTF_8));
        out.writeBytes(raw);
        out.write('e');
        var verified=VerifiedSwarmPrivacy.verifyTorrent(out.toByteArray(),hash(raw));
        assertEquals(java.util.List.of(tracker),verified.publicTrackerUrls());
        var ownerPermit=verified.requirePublicPermit(verified.publicTrackerUrls());
        assertEquals(java.util.List.of(java.net.URI.create(tracker)),ownerPermit.trackers());
        assertTrue(verified.requirePublicPermit().trackers().isEmpty());
        assertTrue(VerifiedSwarmPrivacy.verifyRawInfo(raw,hash(raw))
                .publicTrackerUrls().isEmpty());
        assertThrows(SecurityException.class,()->verified.requirePublicPermit(
                java.util.List.of("file:///etc/passwd")));
        assertThrows(SecurityException.class,()->verified.requirePublicPermit(
                java.util.List.of("http://user:secret@tracker.example/announce")));
    }

    @Test void rejectsMismatchedHashAndMalformedPrivacyFlag() throws Exception {
        byte[] raw=info(0L);
        assertThrows(SecurityException.class,()->VerifiedSwarmPrivacy.verifyRawInfo(raw,"0".repeat(40)));
        assertFalse(VerifiedSwarmPrivacy.verifyRawInfo(raw,hash(raw)).privateTorrent());
        byte[] illegal=info(2L);
        assertThrows(IllegalArgumentException.class,
                ()->VerifiedSwarmPrivacy.verifyTorrent(fullTorrent(illegal),hash(illegal)));
    }
}
