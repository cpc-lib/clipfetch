package cc.ivera.magnet.engine.piece;

import cc.ivera.magnet.domain.TorrentNativeTaskMapper;
import cc.ivera.magnet.domain.TorrentNativeWorkerInstanceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Explicit manual-only orphan fencing. An expired heartbeat is necessary, not
 * sufficient, for automatic takeover. This method NEVER starts another Worker.
 */
@Service
public class NativeExpiredOwnerFenceService {
    private final TorrentNativeTaskMapper tasks;
    private final TorrentNativeWorkerInstanceMapper instances;
    private final cc.ivera.magnet.domain.TorrentNativeRecoveryAuditMapper audit;
    private final org.springframework.transaction.support.TransactionTemplate recoveryTransaction;
    @org.springframework.beans.factory.annotation.Value("${spring.datasource.url:jdbc:sqlite:}")
    private String datasourceUrl;
    private boolean mysql() {return datasourceUrl!=null && datasourceUrl.startsWith("jdbc:mysql:");}

    @org.springframework.beans.factory.annotation.Autowired
    public NativeExpiredOwnerFenceService(TorrentNativeTaskMapper tasks,
            TorrentNativeWorkerInstanceMapper instances,
            cc.ivera.magnet.domain.TorrentNativeRecoveryAuditMapper audit,
            org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.tasks=tasks;
        this.instances=instances;
        this.audit=audit;
        this.recoveryTransaction=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        this.recoveryTransaction.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Test-only manual session boundary. Never register this constructor as a Spring bean. */
    NativeExpiredOwnerFenceService(TorrentNativeTaskMapper tasks,
            TorrentNativeWorkerInstanceMapper instances,
            cc.ivera.magnet.domain.TorrentNativeRecoveryAuditMapper audit) {
        this.tasks=tasks;this.instances=instances;this.audit=audit;
        this.recoveryTransaction=null;
    }

    public boolean fenceOnlyIfExpired(long owner,UUID taskId,long expectedRevision)
            throws IOException {
        if(owner<=0 || taskId==null || expectedRevision<0)
            throw new IllegalArgumentException("Invalid orphan fence request");
        var snapshot=tasks.findOwned(taskId.toString(),owner);
        if(snapshot==null || !"RUNNING".equals(snapshot.getStatus()) ||
                snapshot.getRevision()!=expectedRevision ||
                snapshot.getExecutionInstanceId()==null)
            return false;
        return NativeCheckpointOwnershipLock.execute(Path.of(snapshot.getStatePath()),()->{
            if(recoveryTransaction==null)
                return fenceWithinTransaction(owner,taskId,expectedRevision,snapshot);
            Boolean result=recoveryTransaction.execute(status ->
                    fenceWithinTransaction(owner,taskId,expectedRevision,snapshot));
            return Boolean.TRUE.equals(result);
        });
    }

    private boolean fenceWithinTransaction(long owner,UUID taskId,long expectedRevision,
            cc.ivera.magnet.domain.TorrentNativeTask snapshot) {
            var latest=tasks.findOwned(taskId.toString(),owner);
            if(latest==null || !"RUNNING".equals(latest.getStatus()) ||
                    latest.getRevision()!=expectedRevision ||
                    !snapshot.getExecutionInstanceId().equals(latest.getExecutionInstanceId()) ||
                    !snapshot.getExecutionEpoch().equals(latest.getExecutionEpoch()))
                return false;
            // Query after the file lock is held so that no concurrent cooperative
            // owner transition can race the lease check and task CAS.
            // Missing heartbeat rows are UNKNOWN, never evidence that an owner died.
            if(instances.isExpiredAtDatabaseTime(latest.getExecutionInstanceId(),mysql())!=1) {
                // Audit an explicit refusal once per task revision, without mutating ownership.
                if(audit.countOutcome(owner,taskId.toString(),expectedRevision,"DENIED_LEASE") == 0)
                    audit.insert(owner,taskId.toString(),expectedRevision,
                            latest.getExecutionEpoch(),latest.getExecutionInstanceId(),"DENIED_LEASE");
                return false;
            }
            if(tasks.transitionCas(taskId.toString(),owner,expectedRevision,
                    "RUNNING","PAUSED")!=1)return false;
            if(audit.insert(owner,taskId.toString(),expectedRevision,
                    latest.getExecutionEpoch(),latest.getExecutionInstanceId(),"FENCED_EXPIRED")!=1)
                throw new IllegalStateException("Native recovery audit was not persisted");
            return true;
    }
}
