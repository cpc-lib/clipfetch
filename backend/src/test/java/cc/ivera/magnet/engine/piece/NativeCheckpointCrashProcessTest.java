package cc.ivera.magnet.engine.piece;

import cc.ivera.magnet.engine.metainfo.PieceLayout;
import cc.ivera.magnet.engine.metainfo.TorrentV1MetainfoParser.TorrentInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Uses a separate JVM that commits one verified Piece and then Runtime.halt().
 * Only an isolated JUnit fixture process is killed; no ClipFetch service is touched.
 */
class NativeCheckpointCrashProcessTest {
    @TempDir Path temp;
    private static final byte[] DATA = "abcdefgh".getBytes(StandardCharsets.US_ASCII);
    private static final String HASH = "3".repeat(40);

    static TorrentInfo fixture() throws Exception {
        byte[] hashes = new byte[40];
        for (int i=0;i<2;i++) {
            byte[] digest = MessageDigest.getInstance("SHA-1")
                    .digest(Arrays.copyOfRange(DATA,i*4,i*4+4));
            System.arraycopy(digest,0,hashes,i*20,20);
        }
        return new TorrentInfo(HASH,"fixture",
                List.of(new PieceLayout.FileEntry(0,"fixture.bin",0,DATA.length)),
                DATA.length,4,hashes);
    }

    public static class CrashWriter {
        public static void main(String[] args) throws Exception {
            Path root=Path.of(args[0]);
            long owner=Long.parseLong(args[1]);
            UUID task=UUID.fromString(args[2]);
            var checkpoint=new VerifiedPieceCheckpoint(fixture(),Set.of(0),root,owner,task);
            checkpoint.commit(0,Arrays.copyOfRange(DATA,0,4),()->{});
            // Explicitly bypass shutdown hooks to simulate abrupt JVM termination.
            Runtime.getRuntime().halt(37);
        }
    }

    @Test void resumedRoundDownloadsOnlyMissingPieceAfterChildJvmCrash() throws Exception {
        UUID task=UUID.randomUUID();
        Path root=temp.resolve("resume").resolve(task.toString());
        String cp=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        String java=Path.of(System.getProperty("java.home"),"bin",
                System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")
                        ?"java.exe":"java").toString();
        Process child=new ProcessBuilder(java,"-cp",cp,CrashWriter.class.getName(),
                root.toString(),"405",task.toString()).redirectErrorStream(true).start();
        try {
            assertTrue(child.waitFor(12,TimeUnit.SECONDS),"Crash fixture timed out");
            assertEquals(37,child.exitValue());
        } finally {if(child.isAlive())child.destroyForcibly();}
        var checkpoint=new VerifiedPieceCheckpoint(fixture(),Set.of(0),root,405,task);
        assertEquals(4,checkpoint.verifiedSelectedBytes());
        var requests=new ArrayList<Integer>();
        PieceDownloadCoordinator.Fetcher fetch=(peer,info,peerId,planner,piece,deadline)->{
            requests.add(piece);
            return Arrays.copyOfRange(DATA,piece*4,piece*4+4);
        };
        var peers=List.of(InetSocketAddress.createUnresolved("8.8.8.8",6881));
        var coordinator=new PieceDownloadCoordinator(checkpoint,fetch,peers,
                new byte[20],1,Duration.ofSeconds(1));
        var round=coordinator.runRound(2);
        assertEquals(List.of(1),requests,"Already verified Piece 0 must not be requested again");
        assertEquals(1,round.attemptedPieces());
        assertEquals(1,round.committedPieces());
        assertEquals(8,round.verifiedSelectedBytes());
        assertEquals(8,new VerifiedPieceCheckpoint(fixture(),Set.of(0),root,405,task)
                .verifiedSelectedBytes());
        assertEquals(0,coordinator.runRound(2).attemptedPieces());
        assertEquals(List.of(1),requests,"Completed download must not request any more Pieces");
    }

    @Test void completedDiskPiecesSurviveReopenAndOnlyCorruptPieceIsFetchedAgain() throws Exception {
        UUID task=UUID.randomUUID();
        Path root=temp.resolve("partial-corruption").resolve(task.toString());
        var initial=new VerifiedPieceCheckpoint(fixture(),Set.of(0),root,509,task);
        initial.commit(0,Arrays.copyOfRange(DATA,0,4),()->{});
        initial.commit(1,Arrays.copyOfRange(DATA,4,8),()->{});
        assertEquals(8,initial.verifiedSelectedBytes());
        // Preserve Piece 0 and corrupt only Piece 1 on disk, as could happen
        // after a torn write or external filesystem modification.
        try(var ch=java.nio.channels.FileChannel.open(
                root.resolve("fixture.bin.clipfetch.part"),StandardOpenOption.WRITE)) {
            ch.position(4);
            ch.write(java.nio.ByteBuffer.wrap(new byte[]{'X'}));
            ch.force(true);
        }
        var reopened=new VerifiedPieceCheckpoint(fixture(),Set.of(0),root,509,task);
        assertEquals(4,reopened.verifiedSelectedBytes());
        assertTrue(reopened.verifiedPieces().get(0));
        assertFalse(reopened.verifiedPieces().get(1));
        var requested=new ArrayList<Integer>();
        PieceDownloadCoordinator.Fetcher fetch=(peer,info,peerId,planner,piece,deadline)->{
            requested.add(piece);
            return Arrays.copyOfRange(DATA,piece*4,piece*4+4);
        };
        var coordinator=new PieceDownloadCoordinator(reopened,fetch,
                List.of(InetSocketAddress.createUnresolved("8.8.8.8",6881)),
                new byte[20],1,Duration.ofSeconds(1));
        assertEquals(1,coordinator.runRound(2).committedPieces());
        assertEquals(List.of(1),requested);
        assertEquals(8,new VerifiedPieceCheckpoint(fixture(),Set.of(0),root,509,task)
                .verifiedSelectedBytes());
    }

    @Test void abruptChildJvmExitPreservesOnlyReverifiedPieces() throws Exception {
        UUID task=UUID.randomUUID();
        Path root=temp.resolve("305").resolve(task.toString());
        String classpath=System.getProperty("surefire.test.class.path",
                System.getProperty("java.class.path"));
        String java=Path.of(System.getProperty("java.home"),"bin",
                System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")
                        ?"java.exe":"java").toString();
        Process child=new ProcessBuilder(java,"-cp",classpath,CrashWriter.class.getName(),
                root.toString(),"305",task.toString()).redirectErrorStream(true).start();
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(15),()->{
                assertTrue(child.waitFor(12,TimeUnit.SECONDS),"Fixture child JVM timed out");
            });
            assertEquals(37,child.exitValue(),"Crash fixture failed: "+
                    new String(child.getInputStream().readAllBytes(),StandardCharsets.UTF_8));
        } finally {
            if(child.isAlive())child.destroyForcibly();
        }

        // A different JVM/Checkpoint instance must inspect the actual bytes.
        var restarted=new VerifiedPieceCheckpoint(fixture(),Set.of(0),root,305,task);
        assertEquals(4,restarted.verifiedSelectedBytes());
        assertThrows(SecurityException.class,()->restarted.rescanOwned(306,task,HASH,root.toString()));
        assertThrows(SecurityException.class,()->restarted.rescanOwned(
                305,UUID.randomUUID(),HASH,root.toString()));

        // Verify corrupt bytes are never reported as successfully downloaded.
        Files.write(root.resolve("fixture.bin.clipfetch.part"),new byte[]{'X'},
                StandardOpenOption.WRITE);
        var repaired=new VerifiedPieceCheckpoint(fixture(),Set.of(0),root,305,task);
        assertEquals(0,repaired.verifiedSelectedBytes());
        assertEquals(0,repaired.completedPieces());
        assertEquals(0,new VerifiedPieceCheckpoint(fixture(),Set.of(0),root,305,task)
                .verifiedSelectedBytes(),"Repaired manifest must survive a second restart");
    }
}
