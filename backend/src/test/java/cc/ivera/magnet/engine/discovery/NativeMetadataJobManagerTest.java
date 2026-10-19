package cc.ivera.magnet.engine.discovery;

import cc.ivera.magnet.engine.metainfo.PieceLayout;
import cc.ivera.magnet.engine.metainfo.TorrentV1MetainfoParser.TorrentInfo;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class NativeMetadataJobManagerTest {
    private static final byte[] PEER_ID=new byte[20];
    private static MetadataResolver.Resolution success() {
        return new MetadataResolver.Resolution(
                new TorrentInfo("0".repeat(40),"fixture",
                        List.of(new PieceLayout.FileEntry(0,"fixture",0,1)),
                        1,1,new byte[20]),1,1);
    }
    @Test void completesMetadataOnlyAndHidesOtherUsersJobs() {
        try(var jobs=new NativeMetadataJobManager((magnet,id,port)->success(),
                1,2,2,Duration.ofSeconds(2))) {
            var jobId=jobs.submit(101L,"magnet:?xt=urn:btih:"+"0".repeat(40),PEER_ID,6881);
            var snapshot=jobs.await(101L,jobId,Duration.ofSeconds(1));
            assertEquals(NativeMetadataJobManager.State.SUCCEEDED,snapshot.state());
            assertEquals("fixture",snapshot.name());
            assertEquals(1,snapshot.totalBytes());
            assertEquals(1,snapshot.fileCount());
            assertThrows(NoSuchElementException.class,()->jobs.status(102L,jobId));
            assertThrows(NoSuchElementException.class,()->jobs.cancel(102L,jobId));
        }
    }
    @Test void queuedJobsCanBeCancelledAndPerUserCapacityIsBounded() throws Exception {
        CountDownLatch began=new CountDownLatch(1);
        CountDownLatch release=new CountDownLatch(1);
        try(var jobs=new NativeMetadataJobManager((uri,id,port)->{
                began.countDown();
                try {release.await(3,TimeUnit.SECONDS);}
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new java.io.IOException(e);}
                return success();
            },1,1,2,Duration.ofSeconds(5))) {
            var first=jobs.submit(1L,"first",PEER_ID,6881);
            assertTrue(began.await(1,TimeUnit.SECONDS));
            var second=jobs.submit(1L,"second",PEER_ID,6881);
            assertThrows(RejectedExecutionException.class,()->jobs.submit(1L,"third",PEER_ID,6881));
            assertEquals(NativeMetadataJobManager.State.CANCELLED,jobs.cancel(1L,second).state());
            release.countDown();
            assertEquals(NativeMetadataJobManager.State.SUCCEEDED,
                    jobs.await(1L,first,Duration.ofSeconds(2)).state());
        }finally {release.countDown();}
    }
    @Test void delayedSuccessPastDeadlineIsStillTimeoutWithoutIntermediatePolling() throws Exception {
        CountDownLatch started=new CountDownLatch(1);
        CountDownLatch finished=new CountDownLatch(1);
        try(var jobs=new NativeMetadataJobManager((uri,id,port)->{
                started.countDown();
                try {Thread.sleep(220);} catch(InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {finished.countDown();}
                return success();
            },1,1,1,Duration.ofMillis(70))) {
            var id=jobs.submit(1L,"slow-but-returned",PEER_ID,6881);
            assertTrue(started.await(1,TimeUnit.SECONDS));
            assertTrue(finished.await(1,TimeUnit.SECONDS));
            assertEquals(NativeMetadataJobManager.State.TIMED_OUT,jobs.status(1L,id).state());
            assertTrue(jobs.status(1L,id).error().contains(".torrent"));
            assertNull(jobs.status(1L,id).infoHash());
        }
    }
    @Test void persistenceFailureNeverBecomesSuccessfulJob() {
        NativeMetadataResultStore broken=new NativeMetadataResultStore() {
            @Override public void save(long userId,java.util.UUID id,
                    cc.ivera.magnet.engine.metainfo.TorrentV1MetainfoParser.TorrentInfo info) {
                throw new IllegalStateException("Simulated disk/database failure");
            }
            @Override public java.util.Optional<Saved> find(long userId,java.util.UUID id) {
                return java.util.Optional.empty();
            }
        };
        try(var jobs=new NativeMetadataJobManager((uri,id,port)->success(),
                broken,1,1,1,Duration.ofSeconds(2))) {
            var uuid=jobs.submit(101L,"magnet",PEER_ID,6881);
            var result=jobs.await(101L,uuid,Duration.ofSeconds(1));
            assertEquals(NativeMetadataJobManager.State.FAILED,result.state());
            assertNull(result.infoHash());
            assertEquals("Metadata persistence failed",result.error());
            assertThrows(IllegalStateException.class,()->jobs.files(101L,uuid,1,20));
        }
    }

    @Test void expiresWhenWaitBudgetIsExhaustedAndDoesNotReportFakeSuccess() throws Exception {
        CountDownLatch wait=new CountDownLatch(1);
        try(var jobs=new NativeMetadataJobManager((uri,id,port)->{
                try {wait.await(2,TimeUnit.SECONDS);}
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new java.io.IOException(e);}
                return success();
            },1,1,1,Duration.ofMillis(120))) {
            var id=jobs.submit(1L,"slow",PEER_ID,6881);
            var snapshot=jobs.await(1L,id,Duration.ofSeconds(1));
            assertEquals(NativeMetadataJobManager.State.TIMED_OUT,snapshot.state());
            assertNull(snapshot.infoHash());
            assertEquals(NativeMetadataJobManager.State.TIMED_OUT,jobs.status(1L,id).state());
        }finally {wait.countDown();}
    }
}
