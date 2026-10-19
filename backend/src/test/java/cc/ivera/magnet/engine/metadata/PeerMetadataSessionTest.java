package cc.ivera.magnet.engine.metadata;

import cc.ivera.magnet.engine.codec.BencodeDecoder.*;
import cc.ivera.magnet.engine.codec.BencodeEncoder;
import cc.ivera.magnet.engine.codec.ByteString;
import cc.ivera.magnet.engine.peer.PeerWireCodec;
import cc.ivera.magnet.engine.tracker.TrackerAddressPolicy;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class PeerMetadataSessionTest {
    @Test void retrievesVerifiedInfoDictionaryFromLoopbackPeer() throws Exception {
        byte[] info="d4:name4:teste".getBytes(StandardCharsets.US_ASCII);
        byte[] hash=MessageDigest.getInstance("SHA-1").digest(info);
        byte[] peerId=new byte[20]; Arrays.fill(peerId,(byte)4);
        var wire=new PeerWireCodec();
        try(ServerSocket server=new ServerSocket(0)) {
            var pool=Executors.newSingleThreadExecutor();
            try {
                var fixture=pool.submit(() -> {
                    try(var client=server.accept()) {
                        client.setSoTimeout(3000);
                        var in=client.getInputStream();
                        var out=client.getOutputStream();
                        byte[] hello=in.readNBytes(PeerWireCodec.HANDSHAKE_LENGTH);
                        assertEquals(PeerWireCodec.HANDSHAKE_LENGTH,hello.length);
                        byte[] reserved=new byte[8]; reserved[5]=0x10;
                        byte[] remoteId=new byte[20]; Arrays.fill(remoteId,(byte)7);
                        out.write(wire.encodeHandshake(new PeerWireCodec.Handshake(reserved,hash,remoteId)).array());
                        readFrame(in); // client's BEP10 extension handshake
                        var ext=new DictionaryValue(Map.of(
                                ByteString.ascii("m"),new DictionaryValue(Map.of(ByteString.ascii("ut_metadata"),new IntegerValue(3))),
                                ByteString.ascii("metadata_size"),new IntegerValue(info.length)));
                        byte[] extPayload=new BencodeEncoder().encode(ext);
                        byte[] extFrame=new byte[extPayload.length+1];
                        System.arraycopy(extPayload,0,extFrame,1,extPayload.length);
                        out.write(wire.encodeMessage(new PeerWireCodec.Message(20,extFrame)).array());
                        var request=readFrame(in);
                        assertEquals(20,request.id());
                        assertEquals(3,request.payload()[0] & 255);
                        var header=new DictionaryValue(Map.of(
                                ByteString.ascii("msg_type"),new IntegerValue(1),
                                ByteString.ascii("piece"),new IntegerValue(0),
                                ByteString.ascii("total_size"),new IntegerValue(info.length)));
                        byte[] prefix=new BencodeEncoder().encode(header);
                        byte[] data=new byte[1+prefix.length+info.length];
                        data[0]=1;
                        System.arraycopy(prefix,0,data,1,prefix.length);
                        System.arraycopy(info,0,data,1+prefix.length,info.length);
                        out.write(wire.encodeMessage(new PeerWireCodec.Message(20,data)).array());
                    }catch(Exception e) { throw new RuntimeException(e); }
                });
                byte[] actual=new PeerMetadataSession(new TrackerAddressPolicy(true)).fetch(
                        new InetSocketAddress("127.0.0.1",server.getLocalPort()),hash,peerId,Duration.ofSeconds(3));
                assertArrayEquals(info,actual);
                fixture.get(5,TimeUnit.SECONDS);
            }finally { pool.shutdownNow(); }
        }
    }
    private static PeerWireCodec.Message readFrame(java.io.InputStream in) throws Exception {
        byte[] length=in.readNBytes(4);
        int count=ByteBuffer.wrap(length).getInt();
        assertTrue(count>=1 && count<65536);
        byte[] payload=in.readNBytes(count);
        ByteBuffer frame=ByteBuffer.allocate(4+count).putInt(count).put(payload);
        frame.flip();
        return new PeerWireCodec().decodeMessage(frame).orElseThrow();
    }
}
