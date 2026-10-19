package cc.ivera.magnet.engine.piece;

import cc.ivera.ClipFetchApplication;
import cc.ivera.magnet.engine.metainfo.PieceLayout;
import cc.ivera.magnet.engine.metainfo.TorrentV1MetainfoParser.TorrentInfo;
import cc.ivera.magnet.domain.TorrentNativeRecoveryAuditMapper;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.WebApplicationType;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.*;

/** Test-only main: actual Spring Boot contexts, shared MySQL, synthetic peer and checkpoint. */
public final class NativeBootTakeoverChild {
    private static final byte[] DATA="abcdefgh".getBytes(StandardCharsets.US_ASCII);
    private static TorrentInfo torrent() throws Exception {
        byte[] hashes=new byte[40];
        for(int i=0;i<2;i++){
            byte[] digest=MessageDigest.getInstance("SHA-1")
                    .digest(Arrays.copyOfRange(DATA,i*4,i*4+4));
            System.arraycopy(digest,0,hashes,i*20,20);
        }
        return new TorrentInfo("3".repeat(40),"fixture",
                List.of(new PieceLayout.FileEntry(0,"fixture.bin",0,8)),8,4,hashes);
    }
    public static void main(String[] args) throws Exception {
        String phase=args[0];
        UUID task=UUID.fromString(args[1]);
        Path root=Path.of(args[2]);
        Path marker=Path.of(args[3]);
        try(var app=new SpringApplicationBuilder(ClipFetchApplication.class)
                .web(WebApplicationType.NONE)
                .properties("spring.flyway.enabled=false",
                        "app.magnet.native-engine.enabled=false",
                        "app.magnet.native-engine.worker-round-enabled=false",
                        "app.magnet.native-engine.async-worker-enabled=false",
                        "app.magnet.native-engine.instance-heartbeat-enabled=true")
                .run("--spring.flyway.enabled=false")) {
            var heartbeat=app.getBean(NativeWorkerInstanceHeartbeat.class);
            var lifecycle=app.getBean(NativeTaskLifecycleService.class);
            var checkpoint=new VerifiedPieceCheckpoint(torrent(),Set.of(0),root,782,task);
            if(!heartbeat.isHealthy())throw new IllegalStateException("Boot heartbeat unhealthy");
            var audit=app.getBean(TorrentNativeRecoveryAuditMapper.class);
            if("A".equals(phase)){
                var ready=lifecycle.prepare(782,task,0);
                var round=new NativeTaskRoundRunner(lifecycle);
                var peer=new PieceDownloadCoordinator(checkpoint,
                        (address,info,peerId,planner,piece,deadline)->{
                            if(piece==1){
                                Files.writeString(marker,"FIRST_PIECE_DURABLE");
                                Runtime.getRuntime().halt(37);
                            }
                            return Arrays.copyOfRange(DATA,piece*4,piece*4+4);
                        },List.of(InetSocketAddress.createUnresolved("192.0.2.55",6881)),
                        new byte[20],1,Duration.ofSeconds(1));
                round.runFenced(782,task,ready.revision(),checkpoint,peer,2,
                        heartbeat.instanceId(),heartbeat::isHealthy);
                throw new IllegalStateException("Expected fail-stop between pieces");
            }
            var running=lifecycle.get(782,task);
            if(!"RUNNING".equals(running.state()) || running.verifiedBytes()!=4)
                throw new IllegalStateException("First Boot progress missing: "+running);
            var guard=app.getBean(NativeExpiredOwnerFenceService.class);
            if(guard.fenceOnlyIfExpired(782,task,running.revision()))
                throw new IllegalStateException("Accepted live original lease");
            String url=System.getenv("DB_URL");
            try(var db=DriverManager.getConnection(url,System.getenv("DB_USER"),
                    System.getenv("DB_PASSWORD"));
                var sql=db.prepareStatement("UPDATE torrent_native_worker_instance SET lease_until='2020-01-01 00:00:00' WHERE instance_id=?")) {
                String oldInstance=app.getBean(cc.ivera.magnet.domain.TorrentNativeTaskMapper.class)
                        .findOwned(task.toString(),782L).getExecutionInstanceId();
                sql.setString(1,oldInstance);
                if(sql.executeUpdate()!=1)throw new IllegalStateException("Expected original lease");
            }
            if(!guard.fenceOnlyIfExpired(782,task,running.revision()))
                throw new IllegalStateException("Expired owner not fenced");
            if(audit.countOutcome(782,task.toString(),running.revision(),"FENCED_EXPIRED")!=1)
                throw new IllegalStateException("Missing unique audit");
            var paused=lifecycle.get(782,task);
            var checked=lifecycle.reconcileStopped(782,task,paused.revision(),checkpoint);
            var ready=lifecycle.prepare(782,task,checked.revision());
            var next=new NativeTaskRoundRunner(lifecycle);
            var peer=new PieceDownloadCoordinator(checkpoint,
                    (address,info,peerId,planner,piece,deadline)->{
                        if(piece!=1)throw new IllegalStateException("Duplicate first Piece");
                        return Arrays.copyOfRange(DATA,piece*4,piece*4+4);
                    },List.of(InetSocketAddress.createUnresolved("192.0.2.55",6881)),
                    new byte[20],1,Duration.ofSeconds(1));
            var finished=next.runFenced(782,task,ready.revision(),checkpoint,peer,1,
                    heartbeat.instanceId(),heartbeat::isHealthy);
            if(!"COMPLETED".equals(finished.task().state()) ||
                    finished.task().verifiedBytes()!=8)
                throw new IllegalStateException("Takeover incomplete: "+finished.task());
            var row=app.getBean(cc.ivera.magnet.domain.TorrentNativeTaskMapper.class)
                    .findOwned(task.toString(),782L);
            if(row.getExecutionEpoch()!=2 || audit.countOutcome(782,task.toString(),
                    running.revision(),"FENCED_EXPIRED")!=1)
                throw new IllegalStateException("Epoch/audit invariant violated");
            Files.writeString(marker,"COMPLETED:8:2:1");
        }
    }
}
