package cc.ivera.magnet.engine.piece;

import cc.ivera.magnet.engine.metainfo.PieceLayout;
import cc.ivera.magnet.engine.metainfo.TorrentV1MetainfoParser.TorrentInfo;
import cc.ivera.magnet.engine.peer.PeerWireCodec;
import cc.ivera.magnet.engine.tracker.TrackerAddressPolicy;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class PeerMultiplexedFragmentedFrameTest {
    @Test void longGapWithinPieceFrameDoesNotDestroyValidTcpPeer() throws Exception {
        var content=new byte[]{31,32,33,34};
        var info=new TorrentInfo("a".repeat(40),"delayed-frame",
                List.of(new PieceLayout.FileEntry(0,"delayed-frame.bin",0,4)),
                4,4,MessageDigest.getInstance("SHA-1").digest(content));
        var planner=new PieceBlockPlanner(info,Set.of(0));
        var session=new PeerPieceDownloadSession(new TrackerAddressPolicy(true),()->true);
        var codec=new PeerWireCodec();
        var executor=Executors.newSingleThreadExecutor();
        try(ServerSocket listener=new ServerSocket(0)) {
            var server=executor.submit(()->{
                try(Socket accepted=listener.accept()) {
                    accepted.setSoTimeout(6500);
                    var in=accepted.getInputStream();
                    var out=accepted.getOutputStream();
                    var hello=codec.decodeHandshake(ByteBuffer.wrap(
                            in.readNBytes(PeerWireCodec.HANDSHAKE_LENGTH))).orElseThrow();
                    var peerId=new byte[20];Arrays.fill(peerId,(byte)4);
                    out.write(codec.encodeHandshake(new PeerWireCodec.Handshake(
                            new byte[8],hello.infoHash(),peerId)).array());
                    if(read(codec,in).id()!=2)throw new IOException("No Interested");
                    out.write(codec.encodeMessage(new PeerWireCodec.Message(5,
                            new byte[]{(byte)0x80})).array());
                    out.write(codec.encodeMessage(new PeerWireCodec.Message(1,
                            new byte[0])).array());
                    out.flush();
                    if(read(codec,in).id()!=6)throw new IOException("No Block request");
                    var body=ByteBuffer.allocate(12).putInt(0).putInt(0).put(content).array();
                    byte[] response=codec.encodeMessage(new PeerWireCodec.Message(7,body)).array();
                    out.write(response,0,5);
                    out.flush();
                    // Old receive() failed after 2s and closed the socket
                    // after seeing only the length header and message ID.
                    Thread.sleep(2400);
                    out.write(response,5,response.length-5);
                    out.flush();
                }catch(Exception ex){throw new RuntimeException(ex);}
            });
            var peer=new InetSocketAddress("127.0.0.1",listener.getLocalPort());
            var pieces=new BitSet();pieces.set(0);
            var owner=Thread.currentThread();
            session.beginTask(23,UUID.randomUUID(),info.infoHash());
            try {
                session.beginRound(owner);
                var result=PeerMultiplexedRound.fetch(owner,session,info,planner,
                        List.of(0),List.of(peer),new byte[20],
                        new PeerPieceAvailability(1,Map.of(peer,pieces)),
                        1,Duration.ofSeconds(7),()->{});
                assertEquals(1,result.verified().size());
                assertArrayEquals(content,result.verified().get(0));
                assertEquals(0,result.failedPeers());
            }finally {session.endRound(owner);session.endTask();}
            server.get(3,TimeUnit.SECONDS);
        }finally {executor.shutdownNow();}
    }
    private static PeerWireCodec.Message read(PeerWireCodec codec,InputStream input)
            throws IOException {
        byte[] prefix=input.readNBytes(4);
        if(prefix.length!=4)throw new EOFException("Incomplete frame prefix");
        int length=ByteBuffer.wrap(prefix).getInt();
        if(length<0 || length>65536)throw new IOException("Excessive frame");
        byte[] payload=input.readNBytes(length);
        if(payload.length!=length)throw new EOFException("Incomplete frame body");
        return codec.decodeMessage(ByteBuffer.wrap(ByteBuffer.allocate(length+4)
                .putInt(length).put(payload).array())).orElseThrow();
    }
}
