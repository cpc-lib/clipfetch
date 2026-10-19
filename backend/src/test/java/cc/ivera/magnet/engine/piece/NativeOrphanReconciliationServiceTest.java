package cc.ivera.magnet.engine.piece;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NativeOrphanReconciliationServiceTest {
    @Test void fencesOrphanBeforeRehashAndNeverStartsWorker() throws IOException {
        var jobs=mock(NativeWorkerJobManager.class);
        var tasks=mock(NativeTaskLifecycleService.class);
        var checkpoint=mock(VerifiedPieceCheckpoint.class);
        var task=UUID.randomUUID();
        var fenced=new NativeTaskLifecycleService.TaskState(task,"PAUSED",8,0,64,0);
        var reconciled=new NativeTaskLifecycleService.TaskState(task,"PAUSED",9,32,64,50);
        when(jobs.recoverOwnedOrphan(17L,task,7)).thenReturn(fenced);
        when(tasks.reconcileStopped(17L,task,8,checkpoint)).thenReturn(reconciled);
        var result=new NativeOrphanReconciliationService(jobs,tasks)
                .recoverAndReverify(17L,task,7,checkpoint);
        assertEquals(9,result.reconciled().revision());
        var order=inOrder(jobs,tasks);
        order.verify(jobs).recoverOwnedOrphan(17L,task,7);
        order.verify(tasks).reconcileStopped(17L,task,8,checkpoint);
        verify(jobs,never()).submit(anyLong(),any(),anyLong(),anyInt());
    }

    @Test void failedFencingCannotReachCheckpoint() {
        var jobs=mock(NativeWorkerJobManager.class);
        var tasks=mock(NativeTaskLifecycleService.class);
        var checkpoint=mock(VerifiedPieceCheckpoint.class);
        var task=UUID.randomUUID();
        when(jobs.recoverOwnedOrphan(17L,task,7))
                .thenThrow(new IllegalStateException("Active worker exists"));
        var service=new NativeOrphanReconciliationService(jobs,tasks);
        assertThrows(IllegalStateException.class,
                ()->service.recoverAndReverify(17L,task,7,checkpoint));
        verifyNoInteractions(tasks);
        assertThrows(IllegalArgumentException.class,
                ()->service.recoverAndReverify(17L,task,7,null));
    }
}
