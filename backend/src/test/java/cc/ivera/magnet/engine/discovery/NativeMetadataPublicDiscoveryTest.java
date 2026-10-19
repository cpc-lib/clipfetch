package cc.ivera.magnet.engine.discovery;

import cc.ivera.magnet.engine.codec.BencodeDecoder.*;
import cc.ivera.magnet.engine.codec.BencodeEncoder;
import cc.ivera.magnet.engine.codec.ByteString;
import cc.ivera.magnet.engine.config.NativeTorrentConfiguration;
import cc.ivera.magnet.engine.config.NativeTorrentProperties;
import cc.ivera.magnet.engine.dht.DhtBootstrapLookup;
import cc.ivera.magnet.engine.metadata.PeerMetadataSession;
import cc.ivera.magnet.engine.tracker.PublicTrackerProbe;
import cc.ivera.magnet.engine.tracker.TrackerAddressPolicy;
import cc.ivera.magnet.engine.tracker.TrackerProtocol;
import cc.ivera.magnet.engine.tracker.TrustedTrackerCatalog;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class NativeMetadataPublicDiscoveryTest {
    @Test void consentedProductionMetadataJobUsesBoundedCatalogWhenDhtIsUnavailable() throws Exception {
        byte[] raw=new BencodeEncoder().encode(new DictionaryValue(Map.of(
                ByteString.ascii("length"),new IntegerValue(4),
                ByteString.ascii("name"),new BytesValue("fixture.bin".getBytes(StandardCharsets.US_ASCII)),
                ByteString.ascii("piece length"),new IntegerValue(4),
                ByteString.ascii("pieces"),new BytesValue(new byte[20]))));
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(raw));
        var policy=new TrackerAddressPolicy(false);
        AtomicInteger trackerCalls=new AtomicInteger();
        var catalog=new TrustedTrackerCatalog(()->
                ("https://tracker.example/1\nhttps://tracker.example/2\n"
                        +"https://tracker.example/3\nhttps://tracker.example/4\n"
                        +"https://tracker.example/5\n").getBytes(StandardCharsets.US_ASCII));
        var probe=new PublicTrackerProbe(catalog,(uri,h,id,port)->{
            trackerCalls.incrementAndGet();
            assertEquals(hash,HexFormat.of().formatHex(h));
            return new TrackerProtocol.AnnounceResponse(60,
                    List.of(InetSocketAddress.createUnresolved("8.8.8.8",6881)));
        },policy);
        var unavailableDht=new DhtBootstrapLookup(
                List.of(InetSocketAddress.createUnresolved("8.8.4.4",6881)),
                (endpoint,id,h,timeout)->{throw new IOException("UDP unavailable");},
                (id,h,seeds)->List.of(),8,Duration.ofMillis(100));
        try(var dht=mockStatic(DhtBootstrapLookup.class);
            var trackers=mockStatic(PublicTrackerProbe.class);
            var sessions=mockConstruction(PeerMetadataSession.class,(session,context)->
                    when(session.fetch(any(),any(),any(),any())).thenReturn(raw))) {
            dht.when(()->DhtBootstrapLookup.publicNetwork(any())).thenReturn(unavailableDht);
            trackers.when(()->PublicTrackerProbe.network(any())).thenReturn(probe);
            var settings=new NativeTorrentProperties();
            try(var jobs=new NativeTorrentConfiguration().nativeMetadataJobManager(
                    settings,NativeMetadataResultStore.disabled())) {
                var job=jobs.submit(7,"magnet:?xt=urn:btih:"+hash,new byte[20],6881);
                var result=jobs.await(7,job,Duration.ofSeconds(2));
                assertEquals(NativeMetadataJobManager.State.SUCCEEDED,result.state(),result.error());
                assertEquals(hash,result.infoHash());
                assertEquals(4,trackerCalls.get(),"Only four catalog Trackers may be queried per lookup");
                assertTrue(cc.ivera.magnet.engine.metainfo.VerifiedSwarmPrivacy.verifyTorrent(
                        jobs.verifiedTorrentBytes(7,job),hash).publicTrackerUrls().isEmpty(),
                        "Catalog tracker URLs must not be attached to shared verified metainfo");
            }
        }
    }
}
