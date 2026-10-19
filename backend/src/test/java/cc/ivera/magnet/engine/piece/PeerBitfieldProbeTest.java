package cc.ivera.magnet.engine.piece;

import cc.ivera.magnet.engine.metainfo.PieceLayout;
import cc.ivera.magnet.engine.metainfo.TorrentV1MetainfoParser.TorrentInfo;
import cc.ivera.magnet.engine.peer.PeerWireCodec;
import cc.ivera.magnet.engine.tracker.TrackerAddressPolicy;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class PeerBitfieldProbeTest {
    private static final PeerWireCodec WIRE=new PeerWireCodec();
    private static TorrentInfo fixture() throws Exception {
        byte[] hash=MessageDigest.getInstance("SHA-1")
                .digest("bitfield".getBytes(StandardCharsets.US_ASCII));
        return new TorrentInfo(HexFormat.of().formatHex(hash),"bitfield",
                List.of(new PieceLayout.FileEntry(0,"f.bin",0,12)),
                12,4,new byte[60]);
    }

    @Test void validBep3BitfieldAndSubsequentHaveAreDecodedInMsbOrder() throws Exception {
        var torrent=fixture();
        var bits=exchange(torrent,new PeerWireCodec.Message(5,new byte[]{(byte)0xa0}),
                new PeerWireCodec.Message(4,ByteBuffer.allocate(4).putInt(1).array()));
        assertTrue(bits.isPresent());
        assertEquals(3,bits.get().cardinality());
        assertTrue(bits.get().get(0));
        assertTrue(bits.get().get(1));
        assertTrue(bits.get().get(2));
        assertFalse(bits.get().get(3));
    }

    @Test void paddingViolationAndMalformedBitfieldAreRejected() throws Exception {
        var torrent=fixture();
        assertThrows(IOException.class,()->exchange(torrent,
                new PeerWireCodec.Message(5,new byte[]{(byte)0xa1})));
        assertThrows(IOException.class,()->exchange(torrent,
                new PeerWireCodec.Message(5,new byte[]{(byte)0xa0,(byte)0x00})));
    }

    @Test void haveWithoutFullBitfieldDoesNotImplyAbsentPieces() throws Exception {
        var torrent=fixture();
        var unknown=exchange(torrent,
                new PeerWireCodec.Message(4,ByteBuffer.allocate(4).putInt(1).array()),
                new PeerWireCodec.Message(1,new byte[0]));
        assertTrue(unknown.isEmpty());
    }

    private static java.util.Optional<java.util.BitSet> exchange(
            TorrentInfo torrent,PeerWireCodec.Message... frames) throws Exception {
        var finished=Executors.newSingleThreadExecutor();
        try(var listener=new ServerSocket(0)) {
            Future<?> server=finished.submit(()->{
                try(var peer=listener.accept()) {
                    peer.setSoTimeout(3000);
                    byte[] handshake=peer.getInputStream().readNBytes(PeerWireCodec.HANDSHAKE_LENGTH);
                    assertEquals(PeerWireCodec.HANDSHAKE_LENGTH,handshake.length);
                    var request=WIRE.decodeHandshake(ByteBuffer.wrap(handshake)).orElseThrow();
                    assertArrayEquals(HexFormat.of().parseHex(torrent.infoHash()),request.infoHash());
                    byte[] theirId=new byte[20];Arrays.fill(theirId,(byte)7);
                    peer.getOutputStream().write(WIRE.encodeHandshake(new PeerWireCodec.Handshake(
                            new byte[8],request.infoHash(),theirId)).array());
                    peer.getOutputStream().flush();
                    // Consume interested frame first.
                    byte[] prefix=peer.getInputStream().readNBytes(4);
                    int length=ByteBuffer.wrap(prefix).getInt();
                    assertEquals(1,length);
                    assertEquals(2,peer.getInputStream().read());
                    for(var frame:frames)
                        peer.getOutputStream().write(WIRE.encodeMessage(frame).array());
                    peer.getOutputStream().write(WIRE.encodeMessage(
                            new PeerWireCodec.Message(1,new byte[0])).array());
                    peer.getOutputStream().flush();
                    Thread.sleep(100);
                }catch(Exception ex){throw new RuntimeException(ex);}
            });
            byte[] id=new byte[20];Arrays.fill(id,(byte)4);
            var client=new PeerPieceDownloadSession(new TrackerAddressPolicy(true));
            try {
                return client.probeBitfield(new InetSocketAddress("127.0.0.1",listener.getLocalPort()),
                        torrent,id,Duration.ofSeconds(2));
            }finally {
                server.get(3,TimeUnit.SECONDS);
            }
        }finally {
            finished.shutdownNow();
        }
    }
}
