package cc.ivera.magnet.engine.piece;

import cc.ivera.magnet.engine.config.NativeTorrentProperties;
import cc.ivera.magnet.engine.metainfo.PieceLayout;
import cc.ivera.magnet.engine.metainfo.TorrentV1MetainfoParser.TorrentInfo;
import cc.ivera.magnet.engine.peer.PeerWireCodec;
import cc.ivera.magnet.engine.tracker.TrackerAddressPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeInboundPeerServerTest {
    @TempDir Path root;
    private static final PeerWireCodec WIRE=new PeerWireCodec();
    private static NativeTorrentProperties settings(int port){
        var props=new NativeTorrentProperties();
        props.setEnabled(true);
        props.setWorkerRoundEnabled(true);
        props.setPeerConnectionReuseEnabled(true);
        props.setInboundTcpEnabled(true);
        props.setInboundBindHost("127.0.0.1");
        props.setInboundListenPort(port);
        return props;
    }
    private static PeerWireCodec.Message frame(InputStream in) throws Exception {
        byte[] length=in.readNBytes(4);
        if(length.length==0)return null;
        assertEquals(4,length.length);
        int n=ByteBuffer.wrap(length).getInt();
        assertTrue(n>=0 && n<65536);
        byte[] body=in.readNBytes(n);
        assertEquals(n,body.length);
        return WIRE.decodeMessage(ByteBuffer.wrap(ByteBuffer.allocate(n+4)
                .putInt(n).put(body).array())).orElseThrow();
    }
    @Test void inboundPublicPeerIsAdoptedAndCanSupplyVerifiedPieceWithNoOutboundDial()
            throws Exception {
        byte[] payload=new byte[]{1,2,3,4};
        byte[] digest=MessageDigest.getInstance("SHA-1").digest(payload);
        String hash=HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-1").digest("inbound-torrent-info".getBytes()));
        var torrent=new TorrentInfo(hash,"inbound",
                List.of(new PieceLayout.FileEntry(0,"inbound.bin",0,4)),4,4,digest);
        var session=new PeerPieceDownloadSession(new TrackerAddressPolicy(true),()->true);
        var peerId=new byte[20];
        Arrays.fill(peerId,(byte)7);
        var task=UUID.randomUUID();
        try(ServerSocket reserve=new ServerSocket(0)) {
            int port=reserve.getLocalPort();
            reserve.close();
            try(var server=new NativeInboundPeerServer(session,settings(port));
                Socket remote=new Socket()) {
                session.beginTask(15,task,hash,peerId,1);
                try {
                    server.start();
                    remote.connect(new InetSocketAddress("127.0.0.1",server.listeningPort()));
                    remote.setSoTimeout(3000);
                    var in=remote.getInputStream();
                    var out=remote.getOutputStream();
                    byte[] foreign=new byte[20];
                    Arrays.fill(foreign,(byte)6);
                    out.write(WIRE.encodeHandshake(new PeerWireCodec.Handshake(
                            new byte[8],HexFormat.of().parseHex(hash),foreign)).array());
                    var reply=in.readNBytes(PeerWireCodec.HANDSHAKE_LENGTH);
                    assertEquals(PeerWireCodec.HANDSHAKE_LENGTH,reply.length);
                    assertArrayEquals(peerId,WIRE.decodeHandshake(ByteBuffer.wrap(reply))
                            .orElseThrow().peerId());
                    assertEquals(2,frame(in).id());
                    out.write(WIRE.encodeMessage(new PeerWireCodec.Message(
                            5,new byte[]{(byte)0x80})).array());
                    out.write(WIRE.encodeMessage(new PeerWireCodec.Message(1,new byte[0])).array());
                    out.flush();
                    long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
                    while(session.inboundPeers(15,task).isEmpty() &&
                            System.nanoTime()<deadline)Thread.sleep(5);
                    assertEquals(1,server.acceptedCount());
                    var peers=session.inboundPeers(15,task);
                    assertEquals(1,peers.size());
                    assertTrue(session.inboundPeers(16,task).isEmpty());
                    var verify=new VerifiedPieceCheckpoint(torrent,Set.of(0),
                            root,15,task);
                    var peerThread=new Thread(()->{
                        try {
                            var message=frame(in);
                            if(message==null || message.id()!=6)
                                throw new AssertionError("Expected Piece request");
                            var request=ByteBuffer.wrap(message.payload());
                            assertEquals(0,request.getInt());
                            assertEquals(0,request.getInt());
                            assertEquals(4,request.getInt());
                            var body=ByteBuffer.allocate(12)
                                    .putInt(0).putInt(0).put(payload).array();
                            out.write(WIRE.encodeMessage(
                                    new PeerWireCodec.Message(7,body)).array());
                            out.flush();
                        }catch(Exception error) {throw new RuntimeException(error);}
                    });
                    peerThread.start();
                    var coordinator=new PieceDownloadCoordinator(verify,
                            session::downloadPiece,peers,peerId,1,
                            Duration.ofSeconds(3),1,session);
                    assertEquals(1,coordinator.runRound(1).committedPieces());
                    peerThread.join(2000);
                    assertFalse(peerThread.isAlive());
                    assertEquals(0,session.taskConnectionStats(15,task).crossRoundReuseHits());
                    assertEquals(4,verify.verifiedSelectedBytes());
                }finally {session.endTask();}
            }
        }
    }
    @Test void inactiveOrPublicBindRequiresExplicitOptIn() throws Exception {
        var session=new PeerPieceDownloadSession(new TrackerAddressPolicy(true));
        var props=new NativeTorrentProperties();
        try(var server=new NativeInboundPeerServer(session,props)) {
            server.start();
            assertEquals(0,server.listeningPort());
        }
        var wrong=settings(6899);
        wrong.setInboundBindHost("0.0.0.0");
        try(var server=new NativeInboundPeerServer(session,wrong)) {
            assertThrows(IllegalArgumentException.class,server::start);
            assertEquals(0,server.listeningPort());
        }
    }
}
