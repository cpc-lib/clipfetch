package cc.ivera.magnet.engine.piece;

import cc.ivera.magnet.engine.metainfo.PieceLayout;
import cc.ivera.magnet.engine.metainfo.TorrentV1MetainfoParser.TorrentInfo;
import cc.ivera.magnet.engine.peer.PeerWireCodec;
import cc.ivera.magnet.engine.tracker.TrackerAddressPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** TCP loopback fixture: BEP3 handshake -> interested -> unchoke -> request/piece blocks. */
class PeerPieceDownloadSessionTest {
    @TempDir Path output;
    @Test void downloadsThreeBlocksAndVerifiesWholePieceSha1() throws Exception {
        runFixture(false, output);
    }

    @Test void corruptedNetworkBlockIsRejectedBeforePersistence() throws Exception {
        runFixture(true, output);
    }

    @Test void loopbackPeerDisconnectAndStallDoNotLeakSocketsOrPersistProgress() throws Exception {
        byte[] contents=new byte[]{1,2,3,4};
        byte[] digest=MessageDigest.getInstance("SHA-1").digest(contents);
        byte[] hash=MessageDigest.getInstance("SHA-1").digest("fault-fixture".getBytes());
        var torrent=new TorrentInfo(HexFormat.of().formatHex(hash),"fault",
                List.of(new PieceLayout.FileEntry(0,"fault.bin",0,4)),4,4,digest);
        var planner=new PieceBlockPlanner(torrent,Set.of(0));
        var session=new PeerPieceDownloadSession(new TrackerAddressPolicy(true));
        var state=new VerifiedPieceCheckpoint(torrent,Set.of(0),output,713,java.util.UUID.randomUUID());
        for(boolean stall:new boolean[]{false,true}) {
            try(ServerSocket server=new ServerSocket(0)) {
                var executor=Executors.newSingleThreadExecutor();
                try {
                    var remote=executor.submit(()->{
                        try(var socket=server.accept()) {
                            socket.setSoTimeout(2000);
                            assertEquals(PeerWireCodec.HANDSHAKE_LENGTH,
                                    socket.getInputStream().readNBytes(PeerWireCodec.HANDSHAKE_LENGTH).length);
                            if(stall) {
                                // Client timeout must close the socket; peer observes EOF.
                                assertEquals(-1,socket.getInputStream().read());
                            }
                        }catch(Exception e){throw new RuntimeException(e);}
                    });
                    var peer=new InetSocketAddress("127.0.0.1",server.getLocalPort());
                    assertThrows(IOException.class,()->session.downloadPiece(peer,torrent,
                            new byte[20],planner,0,Duration.ofMillis(250)));
                    remote.get(3,TimeUnit.SECONDS);
                    assertEquals(0,session.activeDirectSocketCount(),
                            "Direct Peer sockets must be unregistered after disconnect or timeout");
                    var counters=session.directSocketCounters();
                    assertEquals(stall?2:1,counters.connected());
                    assertEquals(stall?2:1,counters.closed());
                    assertEquals(stall?2:1,counters.ioFailures());
                    assertEquals(0,counters.registered());
                    assertEquals(0,state.verifiedSelectedBytes());
                    assertFalse(Files.exists(output.resolve("fault.bin.clipfetch.part")));
                }finally {executor.shutdownNow();}
            }
        }
    }

    private static void runFixture(boolean corrupt, Path output) throws Exception {
        byte[] contents=new byte[32773];
        for (int i=0;i<contents.length;i++) contents[i]=(byte)(i*11);
        byte[] digest=MessageDigest.getInstance("SHA-1").digest(contents);
        byte[] hash=MessageDigest.getInstance("SHA-1").digest("fixture".getBytes());
        byte[] localId=new byte[20]; Arrays.fill(localId,(byte)3);
        var torrent=new TorrentInfo(HexFormat.of().formatHex(hash),"example",
                List.of(new PieceLayout.FileEntry(0,"example.bin",0,contents.length)),
                contents.length,contents.length,digest);
        var planner=new PieceBlockPlanner(torrent,Set.of(0));
        var wire=new PeerWireCodec();
        try(ServerSocket server=new ServerSocket(0)) {
            var executor=Executors.newSingleThreadExecutor();
            try {
                var remote=executor.submit(() -> {
                    try(var peer=server.accept()) {
                        peer.setSoTimeout(4000);
                        var in=peer.getInputStream();
                        var out=peer.getOutputStream();
                        byte[] handshake=in.readNBytes(PeerWireCodec.HANDSHAKE_LENGTH);
                        assertEquals(PeerWireCodec.HANDSHAKE_LENGTH,handshake.length);
                        assertArrayEquals(hash,wire.decodeHandshake(ByteBuffer.wrap(handshake)).orElseThrow().infoHash());
                        byte[] remoteId=new byte[20];Arrays.fill(remoteId,(byte)5);
                        out.write(wire.encodeHandshake(new PeerWireCodec.Handshake(
                                new byte[8],hash,remoteId)).array());
                        out.flush();
                        assertEquals(2,readFrame(in,wire).id()); // Interested
                        out.write(wire.encodeMessage(new PeerWireCodec.Message(5,new byte[]{(byte)0x80})).array());
                        out.write(wire.encodeMessage(new PeerWireCodec.Message(1,new byte[0])).array());
                        out.flush();
                        for(var wanted:planner.blocks(0)) {
                            var request=readFrame(in,wire);
                            assertEquals(6,request.id());
                            ByteBuffer payload=ByteBuffer.wrap(request.payload());
                            assertEquals(0,payload.getInt());
                            assertEquals(wanted.begin(),payload.getInt());
                            assertEquals(wanted.length(),payload.getInt());
                            byte[] block=Arrays.copyOfRange(contents,wanted.begin(),wanted.begin()+wanted.length());
                            if (corrupt && wanted.begin()==0) block[0] ^= 1;
                            byte[] body=ByteBuffer.allocate(8+block.length)
                                    .putInt(0).putInt(wanted.begin()).put(block).array();
                            out.write(wire.encodeMessage(new PeerWireCodec.Message(7,body)).array());
                            out.flush();
                        }
                    } catch(Exception e) { throw new RuntimeException(e); }
                });
                var session=new PeerPieceDownloadSession(new TrackerAddressPolicy(true));
                InetSocketAddress addr=new InetSocketAddress("127.0.0.1",server.getLocalPort());
                if (corrupt) {
                    assertThrows(IOException.class,() ->
                            session.downloadPiece(addr,torrent,localId,planner,0,Duration.ofSeconds(3)));
                    assertFalse(Files.exists(output.resolve("example.bin.clipfetch.part")));
                } else {
                    byte[] verified = session.downloadPiece(
                            addr,torrent,localId,planner,0,Duration.ofSeconds(3));
                    assertArrayEquals(contents,verified);
                    var taskId=java.util.UUID.randomUUID();
                    var checkpoint=new VerifiedPieceCheckpoint(torrent,Set.of(0),output,101L,taskId);
                    checkpoint.commit(0,verified,()->{});
                    assertEquals(contents.length,checkpoint.verifiedSelectedBytes());
                    assertArrayEquals(contents,Files.readAllBytes(output.resolve("example.bin.clipfetch.part")));
                    var restarted=new VerifiedPieceCheckpoint(torrent,Set.of(0),output,101L,taskId);
                    assertEquals(contents.length,restarted.verifiedSelectedBytes());
                }
                remote.get(5,TimeUnit.SECONDS);
            } finally { executor.shutdownNow(); }
        }
    }

    private static PeerWireCodec.Message readFrame(java.io.InputStream in,PeerWireCodec codec) throws Exception {
        byte[] header=in.readNBytes(4);
        assertEquals(4,header.length);
        int count=ByteBuffer.wrap(header).getInt();
        assertTrue(count>=1 && count<=64*1024);
        byte[] body=in.readNBytes(count);
        assertEquals(count,body.length);
        ByteBuffer frame=ByteBuffer.allocate(count+4).putInt(count).put(body);
        frame.flip();
        return codec.decodeMessage(frame).orElseThrow();
    }

    @Test void customTimeoutConstructorInitializesProperly() {
        var session = new PeerPieceDownloadSession(new TrackerAddressPolicy(true), () -> true, 3000, 8000, 2000);
        assertNotNull(session);
        assertEquals(0, session.activeDirectSocketCount());
        assertEquals(0, session.registeredReusableSocketCount());
        var counters = session.reusableSocketCounters();
        assertEquals(0, counters.connected());
        assertEquals(0, counters.registered());
    }
}
