package cc.ivera.magnet.engine.pex;

import cc.ivera.magnet.engine.codec.BencodeEncoder;
import cc.ivera.magnet.engine.codec.BencodeDecoder.*;
import cc.ivera.magnet.engine.codec.ByteString;
import cc.ivera.magnet.engine.peer.PeerWireCodec;
import cc.ivera.magnet.engine.tracker.TrackerAddressPolicy;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class PeerExchangeSessionTest {
    @Test void negotiatesUtPexAndReceivesPeersOverRealTcp() throws Exception {
        byte[] hash=new byte[20];Arrays.fill(hash,(byte)3);
        byte[] localId=new byte[20];Arrays.fill(localId,(byte)4);
        var codec=new PeerWireCodec();
        try(var listener=new ServerSocket(0)) {
            var pool=Executors.newSingleThreadExecutor();
            try {
                var remote=pool.submit(() -> {
                    try (var socket=listener.accept()) {
                        socket.setSoTimeout(3000);
                        byte[] received=socket.getInputStream().readNBytes(PeerWireCodec.HANDSHAKE_LENGTH);
                        assertArrayEquals(hash,codec.decodeHandshake(ByteBuffer.wrap(received)).orElseThrow().infoHash());
                        byte[] peerId=new byte[20];Arrays.fill(peerId,(byte)8);
                        byte[] flag=new byte[8];flag[5]=0x10;
                        socket.getOutputStream().write(codec.encodeHandshake(
                                new PeerWireCodec.Handshake(flag,hash,peerId)).array());
                        assertEquals(20,frame(socket.getInputStream()).id());
                        var ext=new DictionaryValue(Map.of(ByteString.ascii("m"),
                                new DictionaryValue(Map.of(ByteString.ascii("ut_pex"),new IntegerValue(7)))));
                        send(socket,0,new BencodeEncoder().encode(ext));
                        var pex=new DictionaryValue(Map.of(
                                ByteString.ascii("added"),new BytesValue(new byte[]{8,8,8,8,26,(byte)225}),
                                ByteString.ascii("added.f"),new BytesValue(new byte[]{2})));
                        send(socket,2,new BencodeEncoder().encode(pex));
                    } catch(Exception e) {throw new RuntimeException(e);}
                });
                var discovered=new PeerExchangeSession(new TrackerAddressPolicy(true)).collect(
                        new InetSocketAddress("127.0.0.1",listener.getLocalPort()),
                        hash,localId,Duration.ofSeconds(3),true);
                assertEquals("8.8.8.8",discovered.added().getFirst().getHostString());
                assertEquals(6881,discovered.added().getFirst().getPort());
                remote.get(5,TimeUnit.SECONDS);
            } finally {pool.shutdownNow();}
        }
    }
    @Test void privateSwarmCannotUsePex() {
        assertThrows(SecurityException.class,()->new PeerExchangeSession(new TrackerAddressPolicy(true))
                .collect(new InetSocketAddress("127.0.0.1",9999),new byte[20],
                        new byte[20],Duration.ofSeconds(1),false));
    }
    private static PeerWireCodec.Message frame(java.io.InputStream in) throws Exception {
        byte[] head=in.readNBytes(4);int n=ByteBuffer.wrap(head).getInt();
        var b=ByteBuffer.allocate(n+4).putInt(n).put(in.readNBytes(n));
        b.flip();
        return new PeerWireCodec().decodeMessage(b).orElseThrow();
    }
    private static void send(java.net.Socket socket,int ext,byte[] data) throws Exception {
        byte[] body=new byte[data.length+1];body[0]=(byte)ext;
        System.arraycopy(data,0,body,1,data.length);
        socket.getOutputStream().write(new PeerWireCodec().encodeMessage(
                new PeerWireCodec.Message(20,body)).array());
        socket.getOutputStream().flush();
    }
}
