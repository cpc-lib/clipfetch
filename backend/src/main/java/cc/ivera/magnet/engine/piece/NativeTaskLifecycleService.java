package cc.ivera.magnet.engine.piece;

import cc.ivera.magnet.domain.TorrentNativeTask;
import cc.ivera.magnet.domain.TorrentNativeTaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Internal P4 optimistic-lock control plane (not an HTTP download API).
 * All mutations are scoped by owner, opaque taskUuid, revision and source status.
 * Setting READY merely marks a task eligible for a future Worker; it does NOT
 * launch a Piece downloader. No caller can manufacture completed bytes here.
 */
@Service
@RequiredArgsConstructor
public class NativeTaskLifecycleService {
    private final TorrentNativeTaskMapper mapper;

    public record TaskState(UUID taskUuid,String state,long revision,
                            long verifiedBytes,long selectedBytes,long wireBytes) {
        @com.fasterxml.jackson.annotation.JsonProperty("progressPercent")
        public double progressPercent() {
            return selectedBytes==0?("COMPLETED".equals(state)?100.0:0.0)
                    :Math.min(100.0,100.0*verifiedBytes/selectedBytes);
        }
    }
    public TaskState get(long userId,UUID taskId) {
        return snapshot(owned(userId,taskId));
    }
    /**
     * Read-only paginated owner-scoped task summary. Never exposes statePath,
     * metadata blobs or raw database entities to the browser.
     */
    public java.util.List<TaskState> list(long userId,int page,int pageSize) {
        if(userId<=0)throw new NoSuchElementException("Native tasks unavailable");
        if(page<1 || pageSize<1 || pageSize>30 ||
                (long)(page-1)*pageSize>Integer.MAX_VALUE)
            throw new IllegalArgumentException("Invalid task pagination");
        return mapper.listOwned(userId,pageSize,(page-1)*pageSize)
                .stream().map(NativeTaskLifecycleService::snapshot).toList();
    }
    public TaskState prepare(long userId,UUID taskId,long expectedRevision) {
        var task=owned(userId,taskId);
        assertRevision(task,expectedRevision);
        if(!"CREATED".equals(task.getStatus()) && !"PAUSED".equals(task.getStatus())
                && !"FAILED".equals(task.getStatus()))
            throw new IllegalStateException("Only CREATED, PAUSED or FAILED tasks may be prepared");
        return transition(task,"READY");
    }
    public TaskState pause(long userId,UUID taskId,long expectedRevision) {
        var task=owned(userId,taskId);
        assertRevision(task,expectedRevision);
        if(!"READY".equals(task.getStatus()) && !"RUNNING".equals(task.getStatus()))
            throw new IllegalStateException("Only READY or RUNNING tasks can be paused");
        if(task.getExecutionInstanceId()!=null)
            return lockedTransition(userId,taskId,expectedRevision,task.getStatus(),"PAUSED");
        return transition(task,"PAUSED"); // legacy unbound Worker
    }
    public record ExecutionFence(TaskState task,long epoch,String instanceId) {}

    /**
     * Atomic instance-scoped claim; legacy progress writes cannot update this task.
     * The returned epoch is read back from durable storage after the CAS.
     */
    private ExecutionFence claimFencedUnderLock(long owner,UUID task,long revision,String instanceId) {
        if(instanceId==null || !instanceId.matches("[0-9a-fA-F-]{36}"))
            throw new IllegalArgumentException("Invalid native worker instance");
        if(mapper.claimExecutionFence(task.toString(),owner,revision,instanceId)!=1)
            throw new IllegalStateException("Native execution claim lost CAS");
        var row=owned(owner,task);
        if(!"RUNNING".equals(row.getStatus()) || !instanceId.equals(row.getExecutionInstanceId())
                || row.getExecutionEpoch()==null || row.getExecutionEpoch()<1)
            throw new IllegalStateException("Execution fence readback mismatch");
        return new ExecutionFence(snapshot(row),row.getExecutionEpoch(),instanceId);
    }

    /**
     * Claim under the exact checkpoint lock used by Piece commits. The lock
     * makes cooperative ownership transitions wait for any in-flight disk write.
     */
    public ExecutionFence claimFencedWithDiskLock(long owner,UUID task,long revision,
                                                   String instanceId) throws IOException {
        var row=owned(owner,task);
        if(!"READY".equals(row.getStatus()) || row.getRevision()!=revision)
            throw new IllegalStateException("Task not READY for fenced claim");
        return NativeCheckpointOwnershipLock.execute(java.nio.file.Path.of(row.getStatePath()),
                ()->claimFencedUnderLock(owner,task,revision,instanceId));
    }

    /** Release current execution ownership only after existing Piece writes finish. */
    public TaskState pauseFencedWithDiskLock(long owner,UUID task,long revision,
                                              long epoch,String instanceId) throws IOException {
        var row=owned(owner,task);
        return NativeCheckpointOwnershipLock.executeForCleanup(java.nio.file.Path.of(row.getStatePath()),()->{
            if("COMPLETED".equals(row.getStatus()))return snapshot(row);
            requireFence(owner,task,revision,epoch,instanceId);
            return transition(owned(owner,task),"PAUSED");
        });
    }

    public void requireFence(long owner,UUID task,long revision,long epoch,String instanceId) {
        var row=owned(owner,task);
        if((!"RUNNING".equals(row.getStatus()) && !"COMPLETED".equals(row.getStatus()))
                || row.getRevision()!=revision
                || row.getExecutionEpoch()==null || row.getExecutionEpoch()!=epoch
                || !instanceId.equals(row.getExecutionInstanceId()))
            throw new IllegalStateException("Native execution fence lost");
    }

    /** Only a future native Worker, never the public resume endpoint, may claim RUNNING. */
    public TaskState claimForWorker(long userId,UUID taskId,long expectedRevision) {
        var task=owned(userId,taskId);
        assertRevision(task,expectedRevision);
        if(!"READY".equals(task.getStatus()))
            throw new IllegalStateException("Task is not ready to run");
        if(task.getExecutionInstanceId()!=null || (task.getExecutionEpoch()!=null && task.getExecutionEpoch()>0))
            throw new IllegalStateException("Legacy worker cannot reclaim fenced execution");
        return transition(task,"RUNNING");
    }
    /**
     * A reclaimed process may mark a stale RUNNING row PAUSED only after ensuring
     * no other live Worker owns it (single-process deployment contract).
     */
    public TaskState fenceOrphanedWorker(long userId,UUID taskId,long expectedRevision) {
        var task=owned(userId,taskId);
        assertRevision(task,expectedRevision);
        if(!"RUNNING".equals(task.getStatus()))
            throw new IllegalStateException("Task has no orphaned Worker");
        if(task.getExecutionInstanceId()!=null)
            throw new IllegalStateException("Fenced task recovery requires verified expired-owner lease");
        return transition(task,"PAUSED");
    }

    private TaskState lockedTransition(long owner,UUID taskId,long revision,
                                        String expectedState,String targetState) {
        var row=owned(owner,taskId);
        try {
            return NativeCheckpointOwnershipLock.execute(java.nio.file.Path.of(row.getStatePath()),()->{
                var current=owned(owner,taskId);
                assertRevision(current,revision);
                if(!expectedState.equals(current.getStatus()))
                    throw new IllegalStateException("Task state changed before locked transition");
                return transition(current,targetState);
            });
        }catch(IOException failure) {
            throw new IllegalStateException("Native task ownership lock unavailable",failure);
        }
    }

    /**
     * Checkpoint is re-read from disk and hashed before updating database progress.
     * A concurrent pause/delete increments revision and makes this CAS fail.
     */
    public TaskState syncRunningProgress(long userId,UUID taskId,long expectedRevision,
                                         VerifiedPieceCheckpoint checkpoint,long wireBytes)
            throws IOException {
        var task=owned(userId,taskId);
        assertRevision(task,expectedRevision);
        if(!"RUNNING".equals(task.getStatus()))
            throw new IllegalStateException("Task is not running");
        if(wireBytes<task.getDownloadedWireBytes())
            throw new IllegalArgumentException("Wire bytes cannot move backwards");
        var disk=activeVerified(task,checkpoint);
        long verified=disk.verifiedSelectedBytes();
        if(verified<task.getVerifiedSelectedBytes())
            throw new IllegalStateException("Disk progress decreased: pause and reconcile first");
        boolean allDone=verified==task.getSelectedTotalBytes();
        int updated=mapper.updateProgressCas(task.getTaskUuid(),userId,expectedRevision,
                verified,wireBytes,allDone);
        if(updated!=1)throw new IllegalStateException("Stale Worker revision or task no longer RUNNING");
        return finalizeCompleted(get(userId,taskId),disk);
    }

    public TaskState syncFencedProgress(long owner,UUID taskId,long revision,
                                        long epoch,String instanceId,
                                        VerifiedPieceCheckpoint checkpoint,long wireBytes)
            throws IOException {
        var row=owned(owner,taskId);
        requireFence(owner,taskId,revision,epoch,instanceId);
        if(wireBytes<row.getDownloadedWireBytes())
            throw new IllegalArgumentException("Wire bytes cannot move backwards");
        var disk=activeVerified(row,checkpoint);
        long verified=disk.verifiedSelectedBytes();
        if(verified<row.getVerifiedSelectedBytes())
            throw new IllegalStateException("Verified progress cannot decrease during execution");
        if(mapper.updateFencedProgress(row.getTaskUuid(),owner,revision,epoch,instanceId,
                verified,wireBytes,verified==row.getSelectedTotalBytes())!=1)
            throw new IllegalStateException("Fenced progress update rejected");
        return finalizeCompleted(get(owner,taskId),disk);
    }

    /**
     * Recover from a corrupt or missing .part Piece. Called only without a RUNNING
     * Worker: unlike normal progress, recovered verifiedBytes are allowed to decrease.
     */
    public TaskState reconcileStopped(long userId,UUID taskId,long expectedRevision,
                                      VerifiedPieceCheckpoint checkpoint) throws IOException {
        return reconcileStoppedInternal(userId,taskId,expectedRevision,checkpoint,false);
    }

    /** Worker-only path: a checkpoint opened and fully hashed in this very round.
     * The one-use verification token forbids reuse after a Piece commit or earlier reconcile.
     * Generic API/recovery callers must use reconcileStopped(), which rescans disk.
     */
    TaskState reconcileFreshlyOpened(long userId,UUID taskId,long expectedRevision,
                                    VerifiedPieceCheckpoint checkpoint) throws IOException {
        return reconcileStoppedInternal(userId,taskId,expectedRevision,checkpoint,true);
    }

    /**
     * Bypass a second cold disk hash only during one explicitly owned continuous
     * job. The caller holds the exact checkpoint from the preceding fenced round.
     * Durable CAS state and owner/selection/InfoHash are checked on every entry.
     * Never use for newly submitted jobs, crash recovery, or public endpoints.
     */
    TaskState reconcileContinuing(long userId,UUID taskId,long expectedRevision,
                                  VerifiedPieceCheckpoint checkpoint,TaskState previous) {
        var row=owned(userId,taskId);
        assertRevision(row,expectedRevision);
        if(!"READY".equals(row.getStatus()) || previous==null ||
                !previous.taskUuid().equals(taskId) ||
                !("PAUSED".equals(previous.state()) || "READY".equals(previous.state())) ||
                (expectedRevision!=previous.revision() &&
                 expectedRevision!=previous.revision()+1) ||
                previous.verifiedBytes()!=row.getVerifiedSelectedBytes() ||
                previous.wireBytes()!=row.getDownloadedWireBytes() ||
                previous.selectedBytes()!=row.getSelectedTotalBytes())
            throw new IllegalStateException("Continuous checkpoint state changed or lost ownership");
        var verified=activeVerified(row,checkpoint);
        if(verified.verifiedSelectedBytes()!=row.getVerifiedSelectedBytes())
            throw new IllegalStateException("Continuing checkpoint and database disagree");
        return snapshot(row);
    }

    private TaskState reconcileStoppedInternal(long userId,UUID taskId,long expectedRevision,
                                               VerifiedPieceCheckpoint checkpoint,boolean fresh)
            throws IOException {
        var task=owned(userId,taskId);
        assertRevision(task,expectedRevision);
        if("RUNNING".equals(task.getStatus()))
            throw new IllegalStateException("Pause the Worker before reconciling files");
        var disk=fresh?checkpoint.consumeFreshlyReverified(task.getUserId(),
                UUID.fromString(task.getTaskUuid()),task.getInfoHash(),task.getStatePath())
                :reverified(task,checkpoint);
        if(fresh)verifySelection(task,disk);
        long actual=disk.verifiedSelectedBytes();
        String revisedState=actual==task.getSelectedTotalBytes()
                && task.getSelectedTotalBytes()>0 ? "COMPLETED"
                : "COMPLETED".equals(task.getStatus()) ? "PAUSED" : task.getStatus();
        if(actual==task.getVerifiedSelectedBytes() && revisedState.equals(task.getStatus()))
            return finalizeCompleted(snapshot(task),disk);
        int updated=mapper.reconcileProgressCas(task.getTaskUuid(),userId,expectedRevision,
                task.getStatus(),actual,revisedState);
        if(updated!=1)throw new IllegalStateException("Stale checkpoint reconciliation");
        return finalizeCompleted(get(userId,taskId),disk);
    }

    /**
     * A COMPLETED task has every selected byte durably verified, so the selected
     * .part files are renamed to their real torrent names. The rename is
     * idempotent: a failure is retried by the next round or reconciliation.
     */
    private TaskState finalizeCompleted(TaskState state,VerifiedPieceCheckpoint disk)
            throws IOException {
        if("COMPLETED".equals(state.state()) &&
                disk.verifiedSelectedBytes()>=disk.selectedTotalBytes())
            disk.finalizeVerifiedFiles();
        return state;
    }

    /** Logical deletion only: no physical deletion while Worker may still write. */
    public void logicalDelete(long userId,UUID taskId,long expectedRevision) {
        var task=owned(userId,taskId);
        assertRevision(task,expectedRevision);
        if("RUNNING".equals(task.getStatus()))
            throw new IllegalStateException("Pause task before deleting");
        if(mapper.logicalDeleteCas(task.getTaskUuid(),userId,expectedRevision,task.getStatus())!=1)
            throw new IllegalStateException("Task changed while deleting");
    }

    private VerifiedPieceCheckpoint activeVerified(TorrentNativeTask row,
                                                   VerifiedPieceCheckpoint checkpoint) {
        if(checkpoint==null)throw new IllegalArgumentException("Missing verified checkpoint");
        // Only the owning RUNNING Worker calls this after a durable Piece commit.
        // A cold/recovered task always takes the full on-disk revalidation path.
        return verifySelection(row,checkpoint.activeOwned(row.getUserId(),
                UUID.fromString(row.getTaskUuid()),row.getInfoHash(),row.getStatePath()));
    }
    private VerifiedPieceCheckpoint reverified(TorrentNativeTask row,
                                               VerifiedPieceCheckpoint checkpoint) throws IOException {
        if(checkpoint==null)throw new IllegalArgumentException("Missing verified checkpoint");
        return verifySelection(row,checkpoint.rescanOwned(row.getUserId(),
                UUID.fromString(row.getTaskUuid()),row.getInfoHash(),row.getStatePath()));
    }
    private VerifiedPieceCheckpoint verifySelection(TorrentNativeTask row,VerifiedPieceCheckpoint result) {
        if(result.selectedTotalBytes()!=row.getSelectedTotalBytes() ||
                !sameSelection(row.getSelectedFilesJson(),result.selection()))
            throw new SecurityException("Checkpoint selection does not match persisted task");
        return result;
    }
    private static boolean sameSelection(String saved,java.util.Set<Integer> actual) {
        if(saved==null || saved.length()>100000)return false;
        try {
            var parsed=new com.fasterxml.jackson.databind.ObjectMapper().readTree(saved);
            if(!parsed.isArray() || parsed.size()!=actual.size())return false;
            var unique=new java.util.HashSet<Integer>();
            for(var node:parsed) {
                if(!node.isIntegralNumber() || !node.canConvertToInt())return false;
                unique.add(node.intValue());
            }
            return unique.size()==actual.size() && unique.equals(actual);
        }catch(com.fasterxml.jackson.core.JsonProcessingException e) {
            return false;
        }
    }
    private TaskState transition(TorrentNativeTask task,String target) {
        if(mapper.transitionCas(task.getTaskUuid(),task.getUserId(),task.getRevision(),
                task.getStatus(),target)!=1)
            throw new IllegalStateException("Task transition lost optimistic-lock race");
        return get(task.getUserId(),UUID.fromString(task.getTaskUuid()));
    }
    private TorrentNativeTask owned(long userId,UUID taskId) {
        if(userId<=0 || taskId==null)throw new NoSuchElementException("Native task unavailable");
        var task=mapper.findOwned(taskId.toString(),userId);
        if(task==null)throw new NoSuchElementException("Native task unavailable");
        return task;
    }
    private static void assertRevision(TorrentNativeTask task,long version) {
        if(version<0 || task.getRevision()==null || task.getRevision()!=version)
            throw new IllegalStateException("Stale task revision");
    }
    private static TaskState snapshot(TorrentNativeTask row) {
        return new TaskState(UUID.fromString(row.getTaskUuid()),row.getStatus(),
                row.getRevision(),row.getVerifiedSelectedBytes(),row.getSelectedTotalBytes(),
                row.getDownloadedWireBytes());
    }
}
