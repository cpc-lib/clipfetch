package cc.ivera.magnet.engine.piece;

import cc.ivera.magnet.engine.config.NativeTorrentProperties;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeOwnershipDeploymentGuardTest {
    @Test void remoteFileStoreTypesAreRejectedForOwnershipClaims() {
        for(String type:new String[]{"nfs","nfs4","cifs","smb3","fuse.sshfs","davfs2","9p","afs"})
            assertTrue(NativeCheckpointOwnershipLock.isNetworkFileStore(type),type);
        for(String type:new String[]{"NTFS","ext4","xfs","overlay"})
            assertFalse(NativeCheckpointOwnershipLock.isNetworkFileStore(type),type);
        assertTrue(NativeCheckpointOwnershipLock.isNetworkFileStore(null));
    }

    @Test void windowsUncSharesCannotClaimOrCommitCheckpoint() {
        assertTrue(NativeCheckpointOwnershipLock.isRemotePath(
                java.nio.file.Path.of("\\\\example-server\\share\\native-task")));
        assertTrue(NativeCheckpointOwnershipLock.isRemotePath(
                java.nio.file.Path.of("//example-server/share/native-task")));
        assertFalse(NativeCheckpointOwnershipLock.isRemotePath(
                java.nio.file.Path.of("C:\\local-native-task")));
    }

    @Test void productionRecoveryAndLegacyExecutionRemainDisabledByDefault() {
        var settings=new NativeTorrentProperties();
        assertFalse(settings.isEnabled());
        assertFalse(settings.isInstanceHeartbeatEnabled());
        assertFalse(settings.isLegacyWorkerCompatibilityEnabled());
        assertFalse(settings.isSingleInstanceOrphanRecoveryEnabled());
        assertFalse(settings.isStartupRetryLeaseAuditEnabled());
        assertFalse(settings.isAsyncWorkerEnabled());
    }
}
