package cc.ivera.magnet.domain;

import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Param;

/** Instance heartbeat is informational, never authority to recover tasks. */
public interface TorrentNativeWorkerInstanceMapper {
    String databaseTime();
    default LocalDateTime databaseNow() {
        String raw=databaseTime();
        if(raw==null || raw.isBlank())throw new IllegalStateException("Database clock unavailable");
        return LocalDateTime.parse(raw.trim().replace(' ','T'));
    }
    int register(@Param("id") String id,@Param("token") String token,
                 @Param("now") LocalDateTime now,@Param("until") LocalDateTime until);
    int renew(@Param("id") String id,@Param("token") String token,
              @Param("now") LocalDateTime now,@Param("until") LocalDateTime until);
    int registerAtomic(@Param("id") String id,@Param("token") String token,
                       @Param("mysql") boolean mysql);
    int renewAtomic(@Param("id") String id,@Param("token") String token,
                    @Param("mysql") boolean mysql);
    int isLiveAtDatabaseTime(@Param("id") String id,@Param("mysql") boolean mysql);
    int isExpiredAtDatabaseTime(@Param("id") String id,@Param("mysql") boolean mysql);
    int stop(@Param("id") String id,@Param("token") String token);
    int isLive(@Param("id") String id,@Param("now") LocalDateTime now);
    int isExpired(@Param("id") String id,@Param("now") LocalDateTime now);
}
