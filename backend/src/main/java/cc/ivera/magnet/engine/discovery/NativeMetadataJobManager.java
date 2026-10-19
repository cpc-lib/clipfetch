package cc.ivera.magnet.engine.discovery;

import cc.ivera.magnet.engine.metainfo.TorrentV1MetainfoParser.TorrentInfo;
import cc.ivera.magnet.engine.tracker.TrackerAddressPolicy;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * Experimental on-demand, in-memory metadata-only job manager.
 * This is NOT a torrent download-task service, and it persists no progress.
 *
 * Deadlines/cancellation are enforced on observation (status/await), not by a scheduler.
 * A blocking external socket may remain in the bounded worker until its own I/O timeout.
 */
public final class NativeMetadataJobManager implements AutoCloseable {
    @FunctionalInterface
    public interface Resolver {
        MetadataResolver.Resolution resolve(String magnetUri, byte[] peerId, int listenPort)
                throws IOException;
    }
    public enum State { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED, TIMED_OUT }
    public record Snapshot(UUID jobId, State state, String infoHash, String name,
                           long totalBytes, int fileCount, String error,
                           Instant createdAt) {
        public boolean terminal() {
            return state == State.SUCCEEDED || state == State.FAILED ||
                    state == State.CANCELLED || state == State.TIMED_OUT;
        }
    }
    public record FileSummary(int index,String path,long bytes) {}
    public record FilesPage(UUID jobId,int page,int pageSize,int totalFiles,List<FileSummary> files) {
        public FilesPage { files=List.copyOf(files); }
    }
    private static final int MAX_HISTORY = 200;
    private final Resolver resolver;
    private final NativeMetadataResultStore resultStore;
    private final ThreadPoolExecutor executor;
    private final Map<UUID, Entry> jobs=new LinkedHashMap<>();
    private final int maxPendingPerUser;
    private final Duration totalBudget;
    private volatile boolean closed;

    private static final class Entry {
        final UUID id=UUID.randomUUID();
        final long userId;
        final Instant created=Instant.now();
        final long deadline;
        volatile State state=State.QUEUED;
        volatile TorrentInfo torrent;
        volatile byte[] verifiedTorrentBytes;
        volatile String error;
        volatile Future<?> future;
        Entry(long userId,long deadline) { this.userId=userId;this.deadline=deadline; }
    }

    public NativeMetadataJobManager(Resolver resolver,int concurrentJobs,int queuedJobs,
                                    int maxPendingPerUser,Duration totalBudget) {
        this(resolver,NativeMetadataResultStore.disabled(),concurrentJobs,queuedJobs,
                maxPendingPerUser,totalBudget);
    }

    public NativeMetadataJobManager(Resolver resolver,NativeMetadataResultStore resultStore,
                                    int concurrentJobs,int queuedJobs,
                                    int maxPendingPerUser,Duration totalBudget) {
        if(resultStore==null)throw new IllegalArgumentException("Missing result store");
        this.resultStore=resultStore;
        if (resolver==null || concurrentJobs<1 || concurrentJobs>16 || queuedJobs<1
                || queuedJobs>128 || maxPendingPerUser<1 || maxPendingPerUser>16
                || totalBudget==null || totalBudget.isNegative() || totalBudget.isZero()
                || totalBudget.compareTo(Duration.ofMinutes(5))>0)
            throw new IllegalArgumentException("Invalid metadata worker configuration");
        this.resolver=resolver;
        this.maxPendingPerUser=maxPendingPerUser;
        this.totalBudget=totalBudget;
        this.executor=new ThreadPoolExecutor(concurrentJobs,concurrentJobs,
                0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(queuedJobs),
                runnable -> {
                    Thread thread=new Thread(runnable,"clipfetch-native-metadata");
                    thread.setDaemon(true);
                    return thread;
                },new ThreadPoolExecutor.AbortPolicy());
    }

    public static NativeMetadataJobManager network() {
        var network=MetadataResolver.network(new TrackerAddressPolicy(false));
        return new NativeMetadataJobManager(
                (magnet,id,port)->network.resolve(magnet,id,port,List.of()),
                2,8,2,Duration.ofSeconds(90));
    }

    /** Only authenticated application services should submit jobs with their resolved userId. */
    public synchronized UUID submit(long userId,String magnetUri,byte[] peerId,int listenPort) {
        if(userId<=0 || magnetUri==null || magnetUri.isBlank() || magnetUri.length()>16384
                || peerId==null || peerId.length!=20 || listenPort<1 || listenPort>65535)
            throw new IllegalArgumentException("Invalid metadata request");
        if(closed)throw new IllegalStateException("Metadata job manager closed");
        lazyExpireAndPrune();
        long pending=jobs.values().stream().filter(e ->
                e.userId==userId && (e.state==State.QUEUED || e.state==State.RUNNING)).count();
        if(pending>=maxPendingPerUser)throw new RejectedExecutionException("User metadata concurrency limit");
        if(jobs.size()>=MAX_HISTORY)throw new RejectedExecutionException("Metadata job history is full");
        Entry entry=new Entry(userId,System.nanoTime()+totalBudget.toNanos());
        jobs.put(entry.id,entry);
        byte[] identity=peerId.clone();
        try {
            entry.future=executor.submit(() -> run(entry,magnetUri,identity,listenPort));
        }catch(RejectedExecutionException e) {
            jobs.remove(entry.id);
            throw e;
        }
        return entry.id;
    }
    private void run(Entry entry,String magnetUri,byte[] peerId,int port) {
        synchronized(this) {
            if(entry.state!=State.QUEUED)return;
            entry.state=State.RUNNING;
        }
        try {
            var result=resolver.resolve(magnetUri,peerId,port);
            synchronized(this) {
                expireIfDue(entry);
                if(entry.state==State.RUNNING) {
                    try {
                        // Persist first: never advertise SUCCEEDED before durable write.
                        resultStore.saveVerified(entry.userId,entry.id,result.torrent(),result.verifiedTorrentBytes());
                        entry.torrent=result.torrent();
                        entry.verifiedTorrentBytes=result.verifiedTorrentBytes();
                        entry.state=State.SUCCEEDED;
                    } catch(RuntimeException persistError) {
                        entry.error="Metadata persistence failed";
                        entry.state=State.FAILED;
                    }
                }
            }
        }catch(Exception e) {
            synchronized(this) {
                expireIfDue(entry);
                if(entry.state==State.RUNNING) {
                    String prefix=e instanceof IOException
                            ? "Metadata discovery failed" : "Metadata resolver error";
                    String detail=e.getMessage();
                    entry.error=(detail==null || detail.isBlank()) ? prefix : prefix + " - " + detail;
                    entry.state=State.FAILED;
                }
            }
        }
    }

    /** Full verified metainfo is available only to the owner while this JVM holds the successful job. */
    public synchronized byte[] verifiedTorrentBytes(long userId,UUID jobId) {
        if(userId<=0 || jobId==null)throw new NoSuchElementException("Metadata job unavailable");
        Entry e=jobs.get(jobId);
        if(e!=null && e.userId!=userId)throw new NoSuchElementException("Metadata job unavailable");
        if(e!=null && e.state==State.SUCCEEDED && e.verifiedTorrentBytes!=null)
            return e.verifiedTorrentBytes.clone();
        return resultStore.findVerified(userId,jobId)
                .orElseThrow(()->new IllegalStateException("Verified full metadata unavailable; upload .torrent instead"));
    }

    public synchronized Snapshot status(long userId,UUID jobId) {
        Entry e=jobs.get(jobId);
        if(e==null) return restoredSnapshot(userId,jobId);
        if(e.userId!=userId || userId<=0)throw new NoSuchElementException("Metadata job unavailable");
        expireIfDue(e);
        return snapshot(e);
    }
    /** File listing is paged (up to 30 per request) and strictly isolated by authenticated owner. */
    public synchronized FilesPage files(long userId,UUID jobId,int page,int pageSize) {
        if(page<1 || pageSize<1 || pageSize>30)
            throw new IllegalArgumentException("Invalid file pagination");
        Entry e=jobs.get(jobId);
        if(e==null) {
            var stored=findRestored(userId,jobId);
            return pageFiles(jobId,stored.files(),page,pageSize);
        }
        if(e.userId!=userId || userId<=0)throw new NoSuchElementException("Metadata job unavailable");
        expireIfDue(e);
        if(e.state!=State.SUCCEEDED || e.torrent==null)
            throw new IllegalStateException("Metadata not yet available");
        var all=e.torrent.files().stream().map(f->new FileSummary(f.index(),f.path(),f.length())).toList();
        return pageFiles(e.id,all,page,pageSize);
    }

    public synchronized Snapshot cancel(long userId,UUID jobId) {
        Entry e=jobs.get(jobId);
        if(e==null)return restoredSnapshot(userId,jobId); // immutable successful persisted result
        if(e.userId!=userId || userId<=0)throw new NoSuchElementException("Metadata job unavailable");
        expireIfDue(e);
        if(e.state==State.QUEUED || e.state==State.RUNNING) {
            e.state=State.CANCELLED;
            if(e.future!=null)e.future.cancel(true);
        }
        return snapshot(e);
    }
    public Snapshot await(long userId,UUID jobId,Duration maxWait) {
        if(maxWait==null || maxWait.isNegative() || maxWait.compareTo(Duration.ofSeconds(30))>0)
            throw new IllegalArgumentException("Invalid await timeout");
        Future<?> future;
        long duration;
        synchronized(this) {
            Entry e=owned(userId,jobId);
            expireIfDue(e);
            if(snapshot(e).terminal())return snapshot(e);
            future=e.future;
            duration=Math.max(0,Math.min(maxWait.toNanos(),e.deadline-System.nanoTime()));
        }
        if(future!=null) {
            try { future.get(duration,TimeUnit.NANOSECONDS); }
            catch(TimeoutException | CancellationException | ExecutionException ignored) {}
            catch(InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        return status(userId,jobId);
    }
    private NativeMetadataResultStore.Saved findRestored(long userId,UUID jobId) {
        if(userId<=0 || jobId==null)throw new NoSuchElementException("Metadata job unavailable");
        return resultStore.find(userId,jobId)
                .orElseThrow(()->new NoSuchElementException("Metadata job unavailable"));
    }
    private Snapshot restoredSnapshot(long userId,UUID jobId) {
        var saved=findRestored(userId,jobId);
        return new Snapshot(saved.jobId(),State.SUCCEEDED,saved.infoHash(),saved.name(),
                saved.totalBytes(),saved.files().size(),null,saved.createdAt());
    }
    private static FilesPage pageFiles(UUID jobId,List<FileSummary> all,int page,int pageSize) {
        long offset=(long)(page-1)*pageSize;
        if(offset>=all.size())return new FilesPage(jobId,page,pageSize,all.size(),List.of());
        int start=(int)offset;
        int end=(int)Math.min(all.size(),offset+pageSize);
        return new FilesPage(jobId,page,pageSize,all.size(),all.subList(start,end));
    }
    private Entry owned(long userId,UUID jobId) {
        Entry e=jobs.get(jobId);
        if(e==null || userId<=0 || e.userId!=userId)
            throw new NoSuchElementException("Metadata job unavailable");
        return e;
    }
    private void expireIfDue(Entry e) {
        if((e.state==State.QUEUED || e.state==State.RUNNING) &&
                System.nanoTime()-e.deadline>=0) {
            e.state=State.TIMED_OUT;
            e.error="元数据解析超时：已达到任务等待上限。部分 Peer 可能无法连接或不支持 BEP9，请重试或上传对应的 .torrent 文件。";
            if(e.future!=null)e.future.cancel(true);
        }
    }
    private void lazyExpireAndPrune() {
        for(Entry e:jobs.values())expireIfDue(e);
        if(jobs.size()>MAX_HISTORY-10) {
            var iterator=jobs.values().iterator();
            while(iterator.hasNext() && jobs.size()>MAX_HISTORY-20) {
                Entry e=iterator.next();
                if(e.state!=State.QUEUED && e.state!=State.RUNNING)iterator.remove();
            }
        }
    }
    private static Snapshot snapshot(Entry e) {
        TorrentInfo info=e.state==State.SUCCEEDED?e.torrent:null;
        return new Snapshot(e.id,e.state,
                info==null?null:info.infoHash(),info==null?null:info.name(),
                info==null?0:info.totalBytes(),info==null?0:info.files().size(),
                e.error,e.created);
    }
    @Override public synchronized void close() {
        if(closed)return;
        closed=true;
        for(Entry e:jobs.values()) {
            if(e.state==State.QUEUED || e.state==State.RUNNING) {
                e.state=State.CANCELLED;
                if(e.future!=null)e.future.cancel(true);
            }
        }
        executor.shutdownNow();
    }
}
