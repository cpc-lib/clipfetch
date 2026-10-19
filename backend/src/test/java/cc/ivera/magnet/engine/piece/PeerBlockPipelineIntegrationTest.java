package cc.ivera.magnet.engine.piece;

import cc.ivera.magnet.engine.metainfo.PieceLayout;
import cc.ivera.magnet.engine.metainfo.TorrentV1MetainfoParser.TorrentInfo;
import cc.ivera.magnet.engine.peer.PeerWireCodec;
import cc.ivera.magnet.engine.tracker.TrackerAddressPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** Loopback BEP3 Peer enforces request backpressure on both transports. */
class PeerBlockPipelineIntegrationTest {
    @TempDir Path output;
    private static final PeerWireCodec CODEC=new PeerWireCodec();
    private static final int BLOCK=PieceBlockPlanner.BLOCK_BYTES;
    private static final int COUNT=20;

    @Test void directPeerRefillsRequestsOnlyAfterAcknowledgement() throws Exception {
        download(false);
    }

    @Test void reusablePeerRefillsRequestsOnlyAfterAcknowledgement() throws Exception {
        download(true);
    }

    private void download(boolean reuse) throws Exception {
        byte[] payload=new byte[BLOCK*COUNT];
        for(int i=0;i<payload.length;i++)payload[i]=(byte)(i*31);
        byte[] digest=MessageDigest.getInstance("SHA-1").digest(payload);
        byte[] hash=MessageDigest.getInstance("SHA-1").digest("window-transport".getBytes());
        var torrent=new TorrentInfo(HexFormat.of().formatHex(hash),"window",
                List.of(new PieceLayout.FileEntry(0,"window.bin",0,payload.length)),
                payload.length,payload.length,digest);
        var planner=new PieceBlockPlanner(torrent,Set.of(0));
        var id=new byte[20];
        var session=new PeerPieceDownloadSession(new TrackerAddressPolicy(true),()->reuse);

        try(ServerSocket server=new ServerSocket(0)) {
            ExecutorService executor=Executors.newSingleThreadExecutor();
            try {
                Future<Integer> remote=executor.submit(()->serve(server,payload,hash));
                var peer=new InetSocketAddress("127.0.0.1",server.getLocalPort());
                if(reuse) {
                    var checkpoint=new VerifiedPieceCheckpoint(torrent,Set.of(0),output,77,UUID.randomUUID());
                    var coordinator=new PieceDownloadCoordinator(checkpoint,session::downloadPiece,
                            List.of(peer),id,1,Duration.ofSeconds(7),1,session);
                    assertEquals(1,coordinator.runRound(1).committedPieces());
                    assertEquals(payload.length,checkpoint.verifiedSelectedBytes());
                }else {
                    assertArrayEquals(payload,session.downloadPiece(peer,torrent,id,planner,0,
                            Duration.ofSeconds(7)));
                }
                assertEquals(COUNT,remote.get(8,TimeUnit.SECONDS));
                assertEquals(0,session.registeredSocketCount());
            }finally {
                executor.shutdownNow();
            }
        }
    }

    private static int serve(ServerSocket server,byte[] payload,byte[] hash)
            throws Exception {
        try(Socket peer=server.accept()) {
            peer.setSoTimeout(6000);
            InputStream in=peer.getInputStream();
            OutputStream out=peer.getOutputStream();
            byte[] handshake=in.readNBytes(PeerWireCodec.HANDSHAKE_LENGTH);
            assertEquals(PeerWireCodec.HANDSHAKE_LENGTH,handshake.length);
            assertArrayEquals(hash,CODEC.decodeHandshake(ByteBuffer.wrap(handshake)).orElseThrow().infoHash());
            byte[] remoteId=new byte[20];
            Arrays.fill(remoteId,(byte)19);
            out.write(CODEC.encodeHandshake(new PeerWireCodec.Handshake(
                    new byte[8],hash,remoteId)).array());
            out.flush();
            assertEquals(2,read(in).id());
            write(out,new PeerWireCodec.Message(5,new byte[]{(byte)0x80}));
            write(out,new PeerWireCodec.Message(1,new byte[0]));

            List<PeerWireCodec.Message> initial=new ArrayList<>();
            for(int i=0;i<PeerBlockRequestWindow.INITIAL;i++) {
                var request=read(in);
                assertEquals(6,request.id());
                initial.add(request);
            }
            // A sender which floods the full Piece instead of respecting the
            // receive window will make this read succeed and fail the test.
            peer.setSoTimeout(120);
            assertThrows(SocketTimeoutException.class,in::read);
            peer.setSoTimeout(6000);
            for(int i=initial.size()-1;i>=0;i--)respond(out,initial.get(i),payload);
            for(int i=initial.size();i<COUNT;i++) {
                var request=read(in);
                assertEquals(6,request.id());
                respond(out,request,payload);
            }
            return COUNT;
        }
    }

    private static void respond(OutputStream out,PeerWireCodec.Message request,byte[] payload)
            throws Exception {
        var parameters=ByteBuffer.wrap(request.payload());
        int piece=parameters.getInt(),begin=parameters.getInt(),length=parameters.getInt();
        assertEquals(0,piece);
        assertEquals(BLOCK,length);
        byte[] block=Arrays.copyOfRange(payload,begin,begin+length);
        var body=ByteBuffer.allocate(8+length).putInt(piece).putInt(begin).put(block).array();
        write(out,new PeerWireCodec.Message(7,body));
    }

    private static void write(OutputStream out,PeerWireCodec.Message message) throws Exception {
        out.write(CODEC.encodeMessage(message).array());
        out.flush();
    }

    private static PeerWireCodec.Message read(InputStream in) throws Exception {
        byte[] prefix=in.readNBytes(4);
        if(prefix.length!=4)throw new EOFException("Missing Peer frame");
        int length=ByteBuffer.wrap(prefix).getInt();
        if(length<1 || length>64*1024)throw new IOException("Invalid Peer frame length");
        byte[] data=in.readNBytes(length);
        if(data.length!=length)throw new EOFException("Truncated Peer frame");
        var whole=ByteBuffer.allocate(length+4).putInt(length).put(data);
        whole.flip();
        return CODEC.decodeMessage(whole).orElseThrow();
    }
}
