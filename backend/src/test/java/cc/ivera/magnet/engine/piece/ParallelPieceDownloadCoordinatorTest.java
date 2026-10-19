package cc.ivera.magnet.engine.piece;

import cc.ivera.magnet.engine.metainfo.PieceLayout;
import cc.ivera.magnet.engine.metainfo.TorrentV1MetainfoParser.TorrentInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class ParallelPieceDownloadCoordinatorTest {
    @TempDir Path root;
    static final byte[] DATA="abcdefghijkl".getBytes(StandardCharsets.US_ASCII);
    static final List<InetSocketAddress> PEERS=List.of(
            InetSocketAddress.createUnresolved("8.8.8.8",6881),
            InetSocketAddress.createUnresolved("9.9.9.9",6881),
            InetSocketAddress.createUnresolved("1.1.1.1",6881));

    private TorrentInfo info() throws Exception {
        byte[] hashes=new byte[60];
        for(int piece=0;piece<3;piece++) {
            var digest=MessageDigest.getInstance("SHA-1").digest(
                    Arrays.copyOfRange(DATA,piece*4,piece*4+4));
            System.arraycopy(digest,0,hashes,piece*20,20);
        }
        return new TorrentInfo("c".repeat(40),"parallel",
                List.of(new PieceLayout.FileEntry(0,"parallel.bin",0,12)),
                12,4,hashes);
    }
    private VerifiedPieceCheckpoint checkpoint() throws Exception {
        return new VerifiedPieceCheckpoint(info(),Set.of(0),root,19,UUID.randomUUID());
    }

    @Test void failingPoolIsRefreshedDuringActiveRoundAndNewPeersDownloadPieces()
            throws Exception {
        final int count=24;
        byte[] content=new byte[count*4];
        byte[] hashes=new byte[count*20];
        for(int i=0;i<count;i++) {
            Arrays.fill(content,i*4,i*4+4,(byte)(i+1));
            byte[] sha=MessageDigest.getInstance("SHA-1").digest(
                    Arrays.copyOfRange(content,i*4,i*4+4));
            System.arraycopy(sha,0,hashes,i*20,20);
        }
        var torrent=new TorrentInfo("c".repeat(40),"refresh",
                List.of(new PieceLayout.FileEntry(0,"refresh.bin",0,content.length)),
                content.length,4,hashes);
        var state=new VerifiedPieceCheckpoint(torrent,Set.of(0),root,19,UUID.randomUUID());
        var failed=InetSocketAddress.createUnresolved("8.8.8.8",6881);
        var unavailable=InetSocketAddress.createUnresolved("9.9.9.9",6881);
        var initial=List.of(failed,unavailable);
        var health=new PeerHealthTracker(initial);
        health.failure(failed);
        health.failure(failed);
        var fresh=new ArrayList<InetSocketAddress>();
        for(int i=1;i<=12;i++)
            fresh.add(InetSocketAddress.createUnresolved("1.1.1."+i,6881));
        var calls=new AtomicInteger();
        var coordinator=new PieceDownloadCoordinator(state,
                (peer,info,id,planner,piece,budget)->{
                    if(initial.contains(peer)) {
                        try {Thread.sleep(250);}
                        catch(InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IOException(e);
                        }
                        throw new IOException("Unresponsive old Peer");
                    }
                    return Arrays.copyOfRange(content,piece*4,piece*4+4);
                },initial,new byte[20],1,Duration.ofSeconds(2),16,
                PieceDownloadCoordinator.ChildThreads.none());
        coordinator.useRoundHealth(health);
        coordinator.enablePeerRefresh(()->{
            calls.incrementAndGet();
            return fresh;
        },24);
        var result=coordinator.runRound(count);
        assertEquals(1,calls.get(),"At most one discovery refresh per round");
        assertTrue(result.committedPieces()>=12,
                "Fresh Peers should be used before the current round finishes");
        assertTrue(health.peerCount()>initial.size());
        assertTrue(coordinator.performance().workerSlots()>=8,
                "New healthy candidates must restore bounded parallelism");
        assertEquals(result.committedPieces(),state.completedPieces());
    }

    @Test void fetchesMultiplePiecesConcurrentlyButCommitsAndCasCallbacksOnOwnerThread()
            throws Exception {
        var state=checkpoint();
        var twoEntered=new CountDownLatch(2);
        var release=new CountDownLatch(1);
        var active=new AtomicInteger();
        var peak=new AtomicInteger();
        var casThreads=ConcurrentHashMap.<Thread>newKeySet();
        var coordinator=new PieceDownloadCoordinator(state,
                (peer,t,id,planner,piece,budget)->{
                    int n=active.incrementAndGet();
                    peak.accumulateAndGet(n,Math::max);
                    try {
                        twoEntered.countDown();
                        if(!release.await(2,TimeUnit.SECONDS))
                            throw new IOException("Parallel requests did not overlap");
                        return Arrays.copyOfRange(DATA,piece*4,piece*4+4);
                    }catch(InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted",e);
                    }finally {active.decrementAndGet();}
                },PEERS,new byte[20],2,Duration.ofSeconds(2),2,
                PieceDownloadCoordinator.ChildThreads.none());
        Thread owner=Thread.currentThread();
        // Releasing only once TWO Peer requests enter proves concurrency.
        var releaser=new Thread(()->{
            try {assertTrue(twoEntered.await(1,TimeUnit.SECONDS));}
            catch(InterruptedException e){Thread.currentThread().interrupt();}
            finally {release.countDown();}
        });
        releaser.start();
        var result=coordinator.runRound(3,(piece,wire,verified)->{
            casThreads.add(Thread.currentThread());
            assertTrue(state.verifiedPieces().get(piece));
            assertEquals(4,wire);
        },()->{});
        releaser.join(1000);
        assertTrue(peak.get()>=2);
        assertEquals(3,result.attemptedPieces());
        assertEquals(3,result.committedPieces());
        assertEquals(12,result.verifiedSelectedBytes());
        assertEquals(Set.of(owner),casThreads,"No network child may update Checkpoint/DB");
        assertArrayEquals(DATA,Files.readAllBytes(root.resolve("parallel.bin.clipfetch.part")));
    }

    @Test void publishesTimedPartialGroupBeforeSlowTailPeersFinish() throws Exception {
        var state=checkpoint();
        var release=new CountDownLatch(1);
        var observedTimedPublication=new AtomicBoolean();
        var completionCalls=new AtomicInteger();
        var coordinator=new PieceDownloadCoordinator(state,
                (peer,info,peerId,planner,piece,budget)->{
                    if(piece!=0) {
                        try {release.await(4,TimeUnit.SECONDS);}
                        catch(InterruptedException stopped) {
                            Thread.currentThread().interrupt();
                            throw new IOException("Timed group test interrupted",stopped);
                        }
                    }
                    return Arrays.copyOfRange(DATA,piece*4,piece*4+4);
                },PEERS,new byte[20],1,Duration.ofSeconds(6),3,
                PieceDownloadCoordinator.ChildThreads.none());
        coordinator.configureGroupCommit(3);
        var report=coordinator.runRound(3,(piece,wire,verified)->{
            completionCalls.incrementAndGet();
            if(release.getCount()!=0)observedTimedPublication.set(true);
            release.countDown();
        },()->{});
        assertEquals(3,report.committedPieces());
        assertTrue(observedTimedPublication.get(),
                "A verified partial group must be published before a slow tail finishes");
        assertEquals(2,completionCalls.get(),
                "Timed partial group and trailing group should commit separately");
        assertEquals(12,state.verifiedSelectedBytes());
        assertEquals(0,state.stagedGroupSize());
    }

    @Test void groupedCommitBatchesCallbacksButPreservesVerifiedCheckpoint() throws Exception {
        UUID task=UUID.randomUUID();
        var state=new VerifiedPieceCheckpoint(info(),Set.of(0),root,19,task);
        var coordinator=new PieceDownloadCoordinator(state,
                (peer,t,id,planner,piece,budget)->
                    Arrays.copyOfRange(DATA,piece*4,piece*4+4),
                PEERS,new byte[20],1,Duration.ofSeconds(2),3,
                PieceDownloadCoordinator.ChildThreads.none());
        coordinator.configureGroupCommit(2);
        var calls=new AtomicInteger();
        var totalWire=new AtomicInteger();
        var progresses=new ArrayList<Long>();
        var round=coordinator.runRound(3,(piece,bytes,verified)->{
            calls.incrementAndGet();
            totalWire.addAndGet(bytes);
            progresses.add(verified);
        },()->{});
        assertEquals(3,round.committedPieces());
        assertEquals(2,calls.get(),"Three Pieces should require only two durable progress CAS calls");
        assertEquals(12,totalWire.get());
        assertEquals(List.of(8L,12L),progresses);
        assertEquals(12,new VerifiedPieceCheckpoint(info(),Set.of(0),root,19,task)
                .verifiedSelectedBytes());
        assertEquals(3,coordinator.performance().workerSlots(),
                "Staged payloads must not consume idle worker slots");
        assertTrue(coordinator.performance().peerFetchMillis()>=0);
    }

    @Test void fourPieceGroupFlushesLastPartialWindowAndNeverCountsBadData()
            throws Exception {
        var checkpoint=checkpoint();
        var fetchCount=new AtomicInteger();
        var coordinator=new PieceDownloadCoordinator(checkpoint,
                (peer,t,id,planner,piece,budget)->{
                    fetchCount.incrementAndGet();
                    if(piece==1)return new byte[]{1,1,1,1};
                    return Arrays.copyOfRange(DATA,piece*4,piece*4+4);
                },PEERS,new byte[20],1,Duration.ofSeconds(2),3,
                PieceDownloadCoordinator.ChildThreads.none());
        coordinator.configureGroupCommit(4);
        var callbacks=new AtomicInteger();
        var bytes=new AtomicInteger();
        var round=coordinator.runRound(3,(piece,wire,total)->{
            callbacks.incrementAndGet();
            bytes.addAndGet(wire);
        },()->{});
        assertEquals(3,round.attemptedPieces());
        assertEquals(2,round.committedPieces());
        assertEquals(List.of(1),round.failedPieces());
        assertEquals(1,callbacks.get(),"Partial tail batch must still be durably committed");
        assertEquals(8,bytes.get());
        assertEquals(8,checkpoint.verifiedSelectedBytes());
    }

    @Test void oneInvalidPieceNeverCountsAsVerifiedAndValidPeersStillProgress() throws Exception {
        UUID taskId=UUID.randomUUID();
        var state=new VerifiedPieceCheckpoint(info(),Set.of(0),root,19,taskId);
        var coordinator=new PieceDownloadCoordinator(state,
                (peer,t,id,planner,piece,budget)->{
                    if(piece==1)return new byte[]{7,7,7,7};
                    return Arrays.copyOfRange(DATA,piece*4,piece*4+4);
                },PEERS,new byte[20],1,Duration.ofSeconds(1),3,
                PieceDownloadCoordinator.ChildThreads.none());
        var round=coordinator.runRound(3);
        assertEquals(3,round.attemptedPieces());
        assertEquals(2,round.committedPieces());
        assertEquals(List.of(1),round.failedPieces());
        assertEquals(8,round.verifiedSelectedBytes());
        assertEquals(2,new VerifiedPieceCheckpoint(info(),Set.of(0),root,19,taskId)
                .completedPieces());
    }

    @Test void all64PieceFetchesCanRunConcurrentlyAndCommitOnlyOnOwnerThread()
            throws Exception {
        int pieceCount=64, bytesPerPiece=4;
        byte[] payload=new byte[pieceCount*bytesPerPiece];
        byte[] hashes=new byte[pieceCount*20];
        var digest=MessageDigest.getInstance("SHA-1");
        for(int piece=0;piece<pieceCount;piece++) {
            for(int j=0;j<bytesPerPiece;j++)
                payload[piece*bytesPerPiece+j]=(byte)(piece+j);
            byte[] sha=digest.digest(Arrays.copyOfRange(payload,
                    piece*bytesPerPiece,(piece+1)*bytesPerPiece));
            System.arraycopy(sha,0,hashes,piece*20,20);
        }
        var torrent=new TorrentInfo("d".repeat(40),"parallel64",
                List.of(new PieceLayout.FileEntry(0,"parallel64.bin",0,payload.length)),
                payload.length,bytesPerPiece,hashes);
        var taskId=UUID.randomUUID();
        var state=new VerifiedPieceCheckpoint(torrent,Set.of(0),root,19,taskId);
        var endpoints=new ArrayList<InetSocketAddress>();
        for(int i=1;i<=64;i++)
            endpoints.add(InetSocketAddress.createUnresolved("192.0.2."+i,6881));
        // Earlier successful rounds have ramped the same owner's adaptive window.
        var health=new PeerHealthTracker(endpoints);
        health.observeRound(8,8);
        health.observeRound(16,16);
        health.observeRound(32,32);
        var entered=new CountDownLatch(pieceCount);
        var release=new CountDownLatch(1);
        var allEntered=new AtomicBoolean();
        var active=new AtomicInteger();
        var peak=new AtomicInteger();
        var casThreads=ConcurrentHashMap.<Thread>newKeySet();
        var coordinator=new PieceDownloadCoordinator(state,
                (peer,info,id,planner,piece,deadline)->{
                    int concurrent=active.incrementAndGet();
                    peak.accumulateAndGet(concurrent,Math::max);
                    try {
                        entered.countDown();
                        if(!release.await(15,TimeUnit.SECONDS))
                            throw new IOException("64 Piece workers never became ready");
                        return Arrays.copyOfRange(payload,piece*bytesPerPiece,
                                (piece+1)*bytesPerPiece);
                    }catch(InterruptedException cancelled) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Cancelled test worker",cancelled);
                    }finally {active.decrementAndGet();}
                },endpoints,new byte[20],1,Duration.ofSeconds(20),64,
                PieceDownloadCoordinator.ChildThreads.none());
        coordinator.useRoundHealth(health);
        coordinator.configureGroupCommit(64);
        var callbacks=new AtomicInteger();
        var releaser=new Thread(()->{
            try {allEntered.set(entered.await(12,TimeUnit.SECONDS));}
            catch(InterruptedException e) {Thread.currentThread().interrupt();}
            finally {release.countDown();}
        });
        releaser.start();
        Thread owner=Thread.currentThread();
        var result=coordinator.runRound(64,(piece,wire,verified)->{
            callbacks.incrementAndGet();
            casThreads.add(Thread.currentThread());
            assertEquals(pieceCount*bytesPerPiece,wire);
            assertTrue(state.verifiedPieces().get(piece));
        },()->{});
        releaser.join(1000);
        assertTrue(allEntered.get(),"Expected all 64 Piece network requests to overlap");
        assertEquals(64,peak.get());
        assertEquals(64,result.attemptedPieces());
        assertEquals(64,result.committedPieces());
        assertEquals(1,callbacks.get(),"Exactly one manifest and one DB callback for 64 Pieces");
        assertEquals(64,coordinator.performance().workerSlots());
        assertEquals(payload.length,result.verifiedSelectedBytes());
        assertEquals(Set.of(owner),casThreads);
        assertArrayEquals(payload,Files.readAllBytes(root.resolve("parallel64.bin.clipfetch.part")));
        assertEquals(64,new VerifiedPieceCheckpoint(torrent,Set.of(0),root,19,
                taskId).completedPieces());
    }

    @Test void parallel64RespectsBoundedInflightMemory() {
        assertEquals(64,PieceDownloadCoordinator.effectiveParallelWorkers(64,64,256*1024));
        assertEquals(64,PieceDownloadCoordinator.effectiveParallelWorkers(64,64,8*1024*1024));
        assertEquals(16,PieceDownloadCoordinator.effectiveParallelWorkers(64,64,32*1024*1024));
        assertEquals(2,PieceDownloadCoordinator.effectiveParallelWorkers(64,2,256*1024));
        assertThrows(IllegalArgumentException.class,
                ()->PieceDownloadCoordinator.effectiveParallelWorkers(257,64,256*1024));
    }

    @Test void callbackFailureStopsFurtherCommitsInsteadOfAllowingChildrenToWrite()
            throws Exception {
        var state=checkpoint();
        var coordinator=new PieceDownloadCoordinator(state,
                (peer,t,id,planner,piece,budget)->
                        Arrays.copyOfRange(DATA,piece*4,piece*4+4),
                PEERS,new byte[20],1,Duration.ofSeconds(1),3,
                PieceDownloadCoordinator.ChildThreads.none());
        var count=new AtomicInteger();
        assertThrows(IOException.class,()->coordinator.runRound(3,(piece,wire,verified)->{
            count.incrementAndGet();
            throw new IOException("Simulated database CAS loss");
        },()->{}));
        assertEquals(1,count.get());
        assertEquals(1,state.completedPieces());
    }

    @Test void groupCommitAcceptsUpTo256Pieces() throws Exception {
        var state=checkpoint();
        var coordinator=new PieceDownloadCoordinator(state,
                (peer,t,id,planner,piece,budget)->
                        Arrays.copyOfRange(DATA,piece*4,piece*4+4),
                PEERS,new byte[20],1,Duration.ofSeconds(1),3,
                PieceDownloadCoordinator.ChildThreads.none());
        assertDoesNotThrow(()->coordinator.configureGroupCommit(1));
        assertDoesNotThrow(()->coordinator.configureGroupCommit(64));
        assertDoesNotThrow(()->coordinator.configureGroupCommit(256));
        assertThrows(IllegalArgumentException.class,()->coordinator.configureGroupCommit(0));
        assertThrows(IllegalArgumentException.class,()->coordinator.configureGroupCommit(257));
    }
}
