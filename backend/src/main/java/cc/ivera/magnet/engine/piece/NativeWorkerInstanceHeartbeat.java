package cc.ivera.magnet.engine.piece;

import cc.ivera.magnet.domain.TorrentNativeWorkerInstanceMapper;
import cc.ivera.magnet.engine.config.NativeTorrentProperties;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/** An opt-in observational instance heartbeat. Never grants orphan ownership. */
@Component
@RequiredArgsConstructor
public class NativeWorkerInstanceHeartbeat {
    private final TorrentNativeWorkerInstanceMapper mapper;
    private final NativeTorrentProperties settings;
    @org.springframework.beans.factory.annotation.Value("${spring.datasource.url:jdbc:sqlite:}")
    private String datasourceUrl;
    private boolean mysql() { return datasourceUrl!=null && datasourceUrl.startsWith("jdbc:mysql:"); }
    private final String id=UUID.randomUUID().toString();
    private final String token=UUID.randomUUID().toString();
    private volatile boolean registered;
    private volatile boolean lost;
    private volatile Runnable onLeaseLost = () -> {};

    public void setOnLeaseLost(Runnable handler) {
        if(handler==null)throw new IllegalArgumentException("Missing lease loss handler");
        onLeaseLost=handler;
    }

    private static final org.slf4j.Logger LOG=
            org.slf4j.LoggerFactory.getLogger(NativeWorkerInstanceHeartbeat.class);
    private void markLost(String reason) {
        if(!lost) {
            lost=true;
            LOG.error("Native instance lease lost: instance={} reason={}; fenced work disabled",
                    id,reason);
            onLeaseLost.run();
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void register() {
        if(!settings.isInstanceHeartbeatEnabled())return;
        if(registered || lost)return;
        if(mapper.registerAtomic(id,token,mysql())!=1) {
            lost=true;
            throw new IllegalStateException("Native instance registration failed");
        }
        registered=true;
    }

    @Scheduled(fixedDelay = 15000)
    public synchronized void heartbeat() {
        if(!settings.isInstanceHeartbeatEnabled() || !registered || lost)return;
        try {
            if(mapper.renewAtomic(id,token,mysql())!=1)markLost("renew rejected or expired");
        }catch(RuntimeException ex) {
            markLost("heartbeat renewal failed: "+ex.getClass().getSimpleName());
            throw ex;
        }
    }

    /** Process-scoped identity, not a grant of distributed task ownership. */
    public String instanceId() {return id;}

    /** Local health must be checked before every new Piece operation. */
    public boolean isHealthy() {
        if(!settings.isInstanceHeartbeatEnabled() || !registered || lost)return false;
        try {
            boolean live=mapper.isLiveAtDatabaseTime(id,mysql())==1;
            if(!live)markLost("database-time lease check rejected");
            return live;
        }catch(RuntimeException failure) {
            markLost("lease health query failed: "+failure.getClass().getSimpleName());
            return false;
        }
    }

    @PreDestroy
    public synchronized void shutdown() {
        if(!registered)return;
        lost=true;
        try {mapper.stop(id,token);}catch(RuntimeException ignored) {}
    }
}
