package cc.ivera.magnet.domain;
import org.apache.ibatis.annotations.Param;
public interface TorrentNativeRecoveryAuditMapper {
 int insert(@Param("userId") long owner,@Param("taskUuid") String task,@Param("revision") long revision,@Param("epoch") long epoch,@Param("instanceId") String instance,@Param("outcome") String outcome);
 int count(@Param("userId") long owner,@Param("taskUuid") String task);
 int countOutcome(@Param("userId") long owner,@Param("taskUuid") String task,@Param("revision") long revision,@Param("outcome") String outcome);
}
