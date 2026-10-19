package cc.ivera.magnet.engine.piece;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NativeOwnedOrphanRecoveryServiceTest {
    @Test void fenceBeforeOpeningOwnedCheckpointAndReconcile() throws IOException {
        var workers=mock(NativeWorkerJobManager.class);
        var factory=mock(NativeTaskCheckpointFactory.class);
        var tasks=mock(NativeTaskLifecycleService.class);
        var checkpoint=mock(VerifiedPieceCheckpoint.class);
        UUID task=UUID.randomUUID();
        var fenced=new NativeTaskLifecycleService.TaskState(task,"PAUSED",12,0,200,0);
        var recovered=new NativeTaskLifecycleService.TaskState(task,"PAUSED",13,100,200,100);
        when(workers.recoverOwnedOrphan(42L,task,11)).thenReturn(fenced);
        when(factory.openOwned(42L,task)).thenReturn(checkpoint);
        when(tasks.reconcileStopped(42L,task,12,checkpoint)).thenReturn(recovered);
        var result=new NativeOwnedOrphanRecoveryService(workers,factory,tasks).recover(42L,task,11);
        assertEquals(100,result.verifiedBytes());
        var order=inOrder(workers,factory,tasks);
        order.verify(workers).recoverOwnedOrphan(42L,task,11);
        order.verify(factory).openOwned(42L,task);
        order.verify(tasks).reconcileStopped(42L,task,12,checkpoint);
        verify(workers,never()).submit(anyLong(),any(),anyLong(),anyInt());
    }

    @Test void failedFenceCannotOpenDiskState() {
        var workers=mock(NativeWorkerJobManager.class);
        var factory=mock(NativeTaskCheckpointFactory.class);
        var tasks=mock(NativeTaskLifecycleService.class);
        UUID task=UUID.randomUUID();
        when(workers.recoverOwnedOrphan(42L,task,11))
                .thenThrow(new IllegalStateException("Not exclusive"));
        assertThrows(IllegalStateException.class,
                ()->new NativeOwnedOrphanRecoveryService(workers,factory,tasks).recover(42L,task,11));
        verifyNoInteractions(factory,tasks);
    }
}
