package cc.ivera.magnet.engine.piece;

import cc.ivera.magnet.engine.metainfo.PieceLayout;
import cc.ivera.magnet.engine.metainfo.TorrentV1MetainfoParser.TorrentInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class VerifiedPieceCheckpointTest {
    @TempDir Path work;
    private static final String HASH="a".repeat(40);

    private static TorrentInfo torrent(byte[] bytes,int pieceBytes,List<PieceLayout.FileEntry> files)
            throws Exception {
        int count=(bytes.length+pieceBytes-1)/pieceBytes;
        byte[] hashes=new byte[count*20];
        for(int p=0;p<count;p++) {
            var piece=Arrays.copyOfRange(bytes,p*pieceBytes,Math.min(bytes.length,(p+1)*pieceBytes));
            var digest=MessageDigest.getInstance("SHA-1").digest(piece);
            System.arraycopy(digest,0,hashes,p*20,20);
        }
        return new TorrentInfo(HASH,"fixture",files,bytes.length,pieceBytes,hashes);
    }

    @Test void committedPiecesSurviveRestartButModifiedFileLosesProgress() throws Exception {
        byte[] all="abcdefghi".getBytes(StandardCharsets.US_ASCII);
        var info=torrent(all,4,List.of(new PieceLayout.FileEntry(0,"data.bin",0,all.length)));
        UUID id=UUID.randomUUID();
        var before=new VerifiedPieceCheckpoint(info,Set.of(0),work,123,id);
        assertEquals(0,before.completedPieces());
        assertEquals(9,before.selectedTotalBytes());
        before.commit(0,Arrays.copyOfRange(all,0,4),()->{});
        before.commit(2,Arrays.copyOfRange(all,8,9),()->{});
        assertEquals(2,before.completedPieces());
        assertEquals(5,before.verifiedSelectedBytes());
        assertTrue(before.verifiedPieces().get(0));
        assertFalse(before.verifiedPieces().get(1));
        var restarted=new VerifiedPieceCheckpoint(info,Set.of(0),work,123,id);
        assertEquals(5,restarted.verifiedSelectedBytes());
        assertEquals(2,restarted.completedPieces());
        // The bitmap may remain intact, but disk corruption MUST be re-verified.
        Files.write(work.resolve("data.bin.clipfetch.part"),new byte[]{(byte)'X'},
                java.nio.file.StandardOpenOption.WRITE);
        var repaired=new VerifiedPieceCheckpoint(info,Set.of(0),work,123,id);
        assertFalse(repaired.verifiedPieces().get(0));
        assertTrue(repaired.verifiedPieces().get(2));
        assertEquals(1,repaired.verifiedSelectedBytes());
        assertEquals(1,new VerifiedPieceCheckpoint(info,Set.of(0),work,123,id).completedPieces());
    }

    @Test void freshWorkerReconciliationTokenIsSingleUseAndNeverSurvivesCommit()
            throws Exception {
        byte[] data="abcdefgh".getBytes(StandardCharsets.US_ASCII);
        var info=torrent(data,4,List.of(new PieceLayout.FileEntry(0,"fresh.bin",0,data.length)));
        UUID task=UUID.randomUUID();
        var opened=new VerifiedPieceCheckpoint(info,Set.of(0),work,222,task);
        String root=work.toAbsolutePath().normalize().toString();
        assertSame(opened,opened.consumeFreshlyReverified(222,task,HASH,root));
        assertThrows(IllegalStateException.class,
                ()->opened.consumeFreshlyReverified(222,task,HASH,root));
        var reopened=new VerifiedPieceCheckpoint(info,Set.of(0),work,222,task);
        assertThrows(SecurityException.class,
                ()->reopened.consumeFreshlyReverified(223,task,HASH,root));
        assertSame(reopened,reopened.consumeFreshlyReverified(222,task,HASH,root));
        var forCommit=new VerifiedPieceCheckpoint(info,Set.of(0),work,222,task);
        forCommit.commit(0,Arrays.copyOf(data,4),()->{});
        assertThrows(IllegalStateException.class,
                ()->forCommit.consumeFreshlyReverified(222,task,HASH,root));
        var forRescan=new VerifiedPieceCheckpoint(info,Set.of(0),work,222,task);
        assertEquals(4,forRescan.rescanOwned(222,task,HASH,root).verifiedSelectedBytes());
        assertThrows(IllegalStateException.class,
                ()->forRescan.consumeFreshlyReverified(222,task,HASH,root));
        assertEquals(4,new VerifiedPieceCheckpoint(info,Set.of(0),work,222,task)
                .verifiedSelectedBytes());
    }

    @Test void groupedCommitPublishesOneManifestAfterAllDataAndRecoversFromCrash()
            throws Exception {
        byte[] data="abcdefgh".getBytes(StandardCharsets.US_ASCII);
        var info=torrent(data,4,List.of(new PieceLayout.FileEntry(0,"group.bin",0,data.length)));
        UUID id=UUID.randomUUID();
        var checkpoint=new VerifiedPieceCheckpoint(info,Set.of(0),work,333,id);
        var batch=List.of(
                new VerifiedPieceCheckpoint.BatchPiece(0,Arrays.copyOfRange(data,0,4)),
                new VerifiedPieceCheckpoint.BatchPiece(1,Arrays.copyOfRange(data,4,8)));
        var checks=new java.util.concurrent.atomic.AtomicInteger();
        assertThrows(IOException.class,()->checkpoint.commitBatch(batch,()->{
            if(checks.incrementAndGet()==4)throw new IOException("Worker fence revoked before second Piece");
        }));
        assertEquals(0,checkpoint.verifiedSelectedBytes(),
                "Aborted batch cannot publish partially written data");
        assertEquals(0,new VerifiedPieceCheckpoint(info,Set.of(0),work,333,id)
                .verifiedSelectedBytes(),"Restart must ignore uncommitted batch payload");
        assertThrows(IllegalArgumentException.class,()->checkpoint.commitBatch(
                List.of(batch.get(0),batch.get(0)),()->{}));
        checkpoint.commitBatch(batch,()->{});
        assertEquals(data.length,checkpoint.verifiedSelectedBytes());
        assertEquals(data.length,new VerifiedPieceCheckpoint(info,Set.of(0),work,333,id)
                .verifiedSelectedBytes());
        assertArrayEquals(data,Files.readAllBytes(work.resolve("group.bin.clipfetch.part")));
    }

    @Test void streamed64PieceGroupIsCrashSafeAndDoesNotExposePendingProgress()
            throws Exception {
        final int pieceSize=4096,pieces=64;
        byte[] data=new byte[pieceSize*pieces];
        for(int i=0;i<data.length;i++)data[i]=(byte)(i*17+i/4096);
        var info=torrent(data,pieceSize,
                List.of(new PieceLayout.FileEntry(0,"stream64.bin",0,data.length)));
        UUID task=UUID.randomUUID();
        var pending=new VerifiedPieceCheckpoint(info,Set.of(0),work,918,task);
        for(int i=0;i<pieces;i++) {
            pending.stageGroupPiece(i,Arrays.copyOfRange(data,i*pieceSize,(i+1)*pieceSize),()->{});
        }
        assertEquals(64,pending.stagedGroupSize());
        assertEquals(data.length,pending.stagedSelectedBytes(),
                "All fsynced Piece payloads are visible as provisional progress");
        assertEquals(0,pending.verifiedSelectedBytes());
        assertThrows(IllegalArgumentException.class,()->pending.stageGroupPiece(
                0,Arrays.copyOf(data,pieceSize),()->{}));
        assertThrows(IOException.class,()->pending.publishStagedGroup(
                ()->{throw new IOException("Execution fence revoked");}));
        assertEquals(0,new VerifiedPieceCheckpoint(info,Set.of(0),work,918,task)
                .verifiedSelectedBytes(),"Unpublished 64-Piece payloads are never claimed after restart");
        pending.discardStagedGroup();
        assertEquals(0,pending.stagedSelectedBytes(),
                "Aborted provisional progress must disappear");
        var recovered=new VerifiedPieceCheckpoint(info,Set.of(0),work,918,task);
        for(int i=0;i<pieces;i++) {
            recovered.stageGroupPiece(i,Arrays.copyOfRange(data,i*pieceSize,(i+1)*pieceSize),()->{});
        }
        recovered.publishStagedGroup(()->{});
        assertEquals(0,recovered.stagedGroupSize());
        assertEquals(0,recovered.stagedSelectedBytes());
        assertEquals(pieces,recovered.completedPieces());
        assertEquals(data.length,new VerifiedPieceCheckpoint(info,Set.of(0),work,918,task)
                .verifiedSelectedBytes());
        assertArrayEquals(data,Files.readAllBytes(work.resolve("stream64.bin.clipfetch.part")));
    }

    @Test void mixedBoundaryUsesShadowButValidatesSelectedFileContent() throws Exception {
        byte[] all="abcdefgh".getBytes(StandardCharsets.US_ASCII);
        var info=torrent(all,8,List.of(
                new PieceLayout.FileEntry(0,"not-selected",0,3),
                new PieceLayout.FileEntry(1,"selected",3,5)));
        UUID task=UUID.randomUUID();
        var checkpoint=new VerifiedPieceCheckpoint(info,Set.of(1),work,55,task);
        checkpoint.commit(0,all,()->{});
        assertEquals(5,checkpoint.verifiedSelectedBytes());
        assertFalse(Files.exists(work.resolve("not-selected.clipfetch.part")));
        assertTrue(Files.exists(work.resolve("selected.clipfetch.part")));
        assertEquals(5,new VerifiedPieceCheckpoint(info,Set.of(1),work,55,task)
                .verifiedSelectedBytes());

        Files.write(work.resolve("selected.clipfetch.part"),
                "QQQQQ".getBytes(StandardCharsets.US_ASCII));
        var afterCorruption=new VerifiedPieceCheckpoint(info,Set.of(1),work,55,task);
        assertEquals(0,afterCorruption.verifiedSelectedBytes());
        assertFalse(afterCorruption.verifiedPieces().get(0));
    }

    @Test void missingBoundaryShadowRejectsProgressAndWrongOwnerFailsClosed() throws Exception {
        byte[] all="abcdefgh".getBytes(StandardCharsets.US_ASCII);
        var info=torrent(all,8,List.of(new PieceLayout.FileEntry(0,"a",0,3),
                new PieceLayout.FileEntry(1,"b",3,5)));
        UUID id=UUID.randomUUID();
        new VerifiedPieceCheckpoint(info,Set.of(1),work,3,id).commit(0,all,()->{});
        assertThrows(IOException.class,()->new VerifiedPieceCheckpoint(info,Set.of(1),work,4,id));
        assertThrows(IOException.class,()->new VerifiedPieceCheckpoint(info,Set.of(1),work,3,UUID.randomUUID()));
        assertThrows(IOException.class,()->new VerifiedPieceCheckpoint(info,Set.of(0),work,3,id));

        try(var shadowFiles=Files.list(work.resolve(".clipfetch-native-state"))) {
            Path shadow=shadowFiles.filter(p->p.getFileName().toString().startsWith("boundary-"))
                    .findFirst().orElseThrow();
            Files.delete(shadow);
        }
        assertEquals(0,new VerifiedPieceCheckpoint(info,Set.of(1),work,3,id).verifiedSelectedBytes());
    }

    @Test void waitingForLockRechecksFenceAndRejectsStaleWorker() throws Exception {
        byte[] bytes="abcd".getBytes(StandardCharsets.US_ASCII);
        var info=torrent(bytes,4,List.of(new PieceLayout.FileEntry(0,"locked.bin",0,4)));
        var checkpoint=new VerifiedPieceCheckpoint(info,Set.of(0),work,161,UUID.randomUUID());
        Path lockFile=work.resolve(".clipfetch-native-state").resolve("piece-commit.lock");
        var allowed=new java.util.concurrent.atomic.AtomicBoolean(true);
        try(var channel=java.nio.channels.FileChannel.open(lockFile,
                java.nio.file.StandardOpenOption.CREATE,java.nio.file.StandardOpenOption.WRITE);
            var lock=channel.lock()) {
            var started=new java.util.concurrent.CountDownLatch(1);
            var executor=java.util.concurrent.Executors.newSingleThreadExecutor();
            try {
                var pending=executor.submit(()->{
                    started.countDown();
                    try {
                        checkpoint.commit(0,bytes,()->{
                            if(!allowed.get())throw new IOException("Execution epoch revoked");
                        });
                        return false;
                    }catch(IOException expected) {return true;}
                });
                assertTrue(started.await(2,java.util.concurrent.TimeUnit.SECONDS));
                allowed.set(false);
                assertTrue(pending.get(3,java.util.concurrent.TimeUnit.SECONDS));
            }finally {executor.shutdownNow();}
        }
        assertEquals(0,checkpoint.completedPieces());
        assertFalse(Files.exists(work.resolve("locked.bin.clipfetch.part")));
    }

    @Test void failedCommitFenceLeavesNoPieceOrManifest() throws Exception {
        byte[] bytes="abcd".getBytes(StandardCharsets.US_ASCII);
        var info=torrent(bytes,4,List.of(new PieceLayout.FileEntry(0,"fenced.bin",0,4)));
        var checkpoint=new VerifiedPieceCheckpoint(info,Set.of(0),work,160,UUID.randomUUID());
        assertThrows(IOException.class,()->checkpoint.commit(0,bytes,
                ()->{throw new IOException("Worker lost execution epoch");}));
        assertEquals(0,checkpoint.completedPieces());
        assertFalse(Files.exists(work.resolve("fenced.bin.clipfetch.part")));
        assertFalse(Files.exists(work.resolve(".clipfetch-native-state").resolve("checkpoint.v1")));
        checkpoint.commit(0,bytes,()->{});
        assertEquals(4,checkpoint.verifiedSelectedBytes());
    }

    @Test void rejectsTamperedPieceAndNeverCreatesCheckpoint() throws Exception {
        byte[] all="abcd".getBytes(StandardCharsets.US_ASCII);
        var info=torrent(all,4,List.of(new PieceLayout.FileEntry(0,"safe.bin",0,4)));
        var checkpoint=new VerifiedPieceCheckpoint(info,Set.of(0),work,100,UUID.randomUUID());
        assertThrows(IOException.class,()->checkpoint.commit(0,"abce".getBytes(StandardCharsets.US_ASCII),()->{}));
        assertEquals(0,checkpoint.completedPieces());
        assertFalse(Files.exists(work.resolve("safe.bin.clipfetch.part")));
        assertFalse(Files.exists(work.resolve(".clipfetch-native-state").resolve("checkpoint.v1")));
    }

    @Test void finalizeRequiresEveryPieceAndSurvivesRestart() throws Exception {
        byte[] all="abcdefghi".getBytes(StandardCharsets.US_ASCII);
        var info=torrent(all,4,List.of(new PieceLayout.FileEntry(0,"data.bin",0,all.length)));
        UUID id=UUID.randomUUID();
        var checkpoint=new VerifiedPieceCheckpoint(info,Set.of(0),work,123,id);
        checkpoint.commit(0,Arrays.copyOfRange(all,0,4),()->{});
        // A partially downloaded torrent must never be presented as finished.
        assertThrows(IllegalStateException.class,checkpoint::finalizeVerifiedFiles);
        assertTrue(Files.exists(work.resolve("data.bin.clipfetch.part")));
        assertFalse(Files.exists(work.resolve("data.bin")));

        checkpoint.commit(1,Arrays.copyOfRange(all,4,8),()->{});
        checkpoint.commit(2,Arrays.copyOfRange(all,8,9),()->{});
        checkpoint.finalizeVerifiedFiles();
        assertEquals(9,Files.size(work.resolve("data.bin")));
        assertFalse(Files.exists(work.resolve("data.bin.clipfetch.part")));
        // Recovery accepts the finalized name, so a restart still trusts the task.
        assertEquals(9,new VerifiedPieceCheckpoint(info,Set.of(0),work,123,id)
                .verifiedSelectedBytes());
    }
}
