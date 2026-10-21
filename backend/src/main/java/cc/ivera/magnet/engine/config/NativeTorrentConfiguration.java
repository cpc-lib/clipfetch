package cc.ivera.magnet.engine.config;

import cc.ivera.magnet.engine.discovery.MetadataResolver;
import cc.ivera.magnet.engine.discovery.NativeMetadataJobManager;
import cc.ivera.magnet.engine.discovery.NativeMetadataResultStore;
import cc.ivera.magnet.engine.tracker.TrackerAddressPolicy;
import cc.ivera.magnet.engine.tracker.PublicTrackerProbe;
import cc.ivera.magnet.engine.piece.NativeTaskWorkerRoundService;
import cc.ivera.magnet.engine.piece.NativeWorkerJobManager;
import cc.ivera.magnet.engine.piece.NativeWorkerInstanceHeartbeat;
import cc.ivera.magnet.engine.piece.NativeTaskCheckpointFactory;
import cc.ivera.magnet.engine.piece.NativeTaskLifecycleService;
import cc.ivera.magnet.engine.piece.PeerPieceDownloadSession;
import cc.ivera.magnet.engine.piece.NativeInboundPeerServer;
import cc.ivera.magnet.engine.discovery.NativePublicPeerFinder;
import cc.ivera.magnet.engine.discovery.PeerDiscoveryAccelerator;
import cc.ivera.magnet.engine.dht.DhtBootstrapLookup;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;

@Configuration
@EnableConfigurationProperties(NativeTorrentProperties.class)
public class NativeTorrentConfiguration {
    /** Shared socket registry lets the async job canceller close active Peer reads. */
    @Bean
    public PeerPieceDownloadSession nativePiecePeerSession(NativeTorrentProperties properties) {
        return new PeerPieceDownloadSession(new TrackerAddressPolicy(false),
                properties::isPeerConnectionReuseEnabled,
                properties.getPeerHandshakeTimeoutMillis(),
                properties.getPeerStallTimeoutMillis(),
                properties.getPeerConnectTimeoutMillis(),
                properties.getPeerMaxInFlightBlocks(),
                properties.getPeerFirstBlockTimeoutMillis());
    }

    /** No listener is bound unless both native engine and inbound TCP are
     * explicitly enabled. Public interface binding needs an extra opt-in.
     */
    @Bean(initMethod="start",destroyMethod="close")
    public NativeInboundPeerServer nativeInboundPeerServer(
            PeerPieceDownloadSession session,NativeTorrentProperties properties) {
        return new NativeInboundPeerServer(session,properties);
    }

    /** No network activity, background thread or download session is created at startup. */
    @Bean
    public NativeTaskWorkerRoundService nativeTaskWorkerRoundService(
            NativeTaskLifecycleService lifecycle,NativeTaskCheckpointFactory checkpoints,
            NativeTorrentProperties properties,PeerPieceDownloadSession peerSession) {
        var policy=new TrackerAddressPolicy(false);
        var trackers=PublicTrackerProbe.network(policy);
        var pex=PeerDiscoveryAccelerator.network(policy);
        var dht=DhtBootstrapLookup.publicNetwork(policy);
        var finder=new NativePublicPeerFinder(
                (hash,peerId,port)->trackers.probe(hash,peerId,port,true,16).peers(),
                (uriList,hash,peerId,port)->trackers.probeUris(
                        hash,peerId,port,uriList,Math.min(32,uriList.size())).peers(),
                pex,policy,
                (localNodeId,hash)->dht.find(localNodeId,hash).peers(),
                properties::isDhtFallbackEnabled,
                true,properties.getMaxPeersPerTask());
        return new NativeTaskWorkerRoundService(lifecycle,checkpoints,properties,
                finder,peerSession::downloadPiece,policy,peerSession,peerSession::probeBitfield);
    }

    /** Enqueue only explicit internal work; constructing this Bean never contacts Peers. */
    @Bean(destroyMethod = "close")
    public NativeWorkerJobManager nativeWorkerJobManager(
            NativeTaskWorkerRoundService worker,NativeTaskLifecycleService lifecycle,
            NativeTorrentProperties properties,PeerPieceDownloadSession peerSession,
            NativeWorkerInstanceHeartbeat heartbeat,
            cc.ivera.magnet.engine.piece.NativeExpiredOwnerFenceService expiredOwnerFence) {
        worker.configureFencedExecution(heartbeat.instanceId(),heartbeat::isHealthy);
        NativeWorkerJobManager.TaskAccess tasks=new NativeWorkerJobManager.TaskAccess() {
            @Override public NativeTaskLifecycleService.TaskState get(long owner,java.util.UUID task) {
                return lifecycle.get(owner,task);
            }
            @Override public NativeTaskLifecycleService.TaskState pause(long owner,
                    java.util.UUID task,long revision) {
                return lifecycle.pause(owner,task,revision);
            }
            @Override public NativeTaskLifecycleService.TaskState prepare(long owner,
                    java.util.UUID task,long revision) {
                return lifecycle.prepare(owner,task,revision);
            }
            @Override public NativeTaskLifecycleService.TaskState fenceOrphan(long owner,
                    java.util.UUID task,long revision) {
                // Instance-bound orphans first require a verified expired owner lease;
                // legacy unbound rows fall through to the original fence path.
                try {
                    if(expiredOwnerFence.fenceOnlyIfExpired(owner,task,revision))
                        return lifecycle.get(owner,task);
                }catch(java.io.IOException failure) {
                    throw new IllegalStateException("Expired-owner fence check failed",failure);
                }
                return lifecycle.fenceOrphanedWorker(owner,task,revision);
            }
        };
        var manager=new NativeWorkerJobManager(worker::runOnce,tasks,properties,
                properties.getAsyncWorkerThreads(),properties.getAsyncWorkerQueueSize(),
                properties.getAsyncWorkerPerUserLimit(),peerSession::abortForThread);
        manager.setContinuousSession(new NativeWorkerJobManager.ContinuousSession() {
            @Override public void begin(long owner,java.util.UUID task) {
                worker.beginContinuousSession(owner,task);
            }
            @Override public void end() {
                worker.endContinuousSession();
            }
        });
        manager.setNetworkTelemetry(peerSession::receivedPayloadBytes);
        manager.setPeerTelemetry(worker::peerStatus);
        manager.setStagedTelemetry(worker::stagedProgress);
        manager.setConnectionTelemetry(peerSession::taskConnectionStats);
        manager.setRequestQueueTelemetry(peerSession::requestTelemetry);
        manager.setInstanceAdmissionHealthy(heartbeat::isHealthy);
        heartbeat.setOnLeaseLost(manager::cancelAllForLostInstance);
        return manager;
    }

    /** Initialized without network activity; no jobs are launched until explicitly enabled and submitted. */
    @Bean(destroyMethod = "close")
    public NativeMetadataJobManager nativeMetadataJobManager(NativeTorrentProperties properties,
                                                            NativeMetadataResultStore resultStore) {
        MetadataResolver resolver=MetadataResolver.network(new TrackerAddressPolicy(false),
                properties.getMetadataPeerAttempts(),
                Duration.ofMillis(properties.getMetadataPeerTimeoutMillis()));
        var magnetParser=new cc.ivera.magnet.engine.magnet.MagnetUriParser();
        return new NativeMetadataJobManager(
                // The authenticated metadata Controller requires public-discovery consent per submission.
                (magnet,peerId,listenPort)->{
                    // Cache-first: a previously verified public InfoHash must not depend
                    // on live Peer availability (dead swarms fail all network attempts).
                    try {
                        String infoHash=magnetParser.parse(magnet).infoHash();
                        var cached=resultStore.findVerifiedByInfoHash(infoHash);
                        if(cached.isPresent()) {
                            var torrent=new cc.ivera.magnet.engine.metainfo.TorrentV1MetainfoParser()
                                    .parse(cached.get());
                            return new MetadataResolver.Resolution(torrent,0,0,cached.get());
                        }
                    }catch(RuntimeException cacheProbeIgnored) {
                        // Fall through to the network path on any cache probe failure.
                    }
                    return resolver.resolveWithPublicDiscoveryConsent(
                            magnet,peerId,listenPort,List.of());
                },
                resultStore, properties.getMetadataConcurrentJobs(),properties.getMetadataQueuedJobs(),
                properties.getMetadataMaxPendingPerUser(),
                Duration.ofSeconds(properties.getMetadataTotalTimeoutSeconds()));
    }
}
