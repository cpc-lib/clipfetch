package cc.ivera.magnet.engine.piece;

import cc.ivera.magnet.engine.config.NativeTorrentProperties;
import cc.ivera.magnet.engine.metainfo.PieceLayout;
import cc.ivera.magnet.engine.metainfo.TorrentV1MetainfoParser.TorrentInfo;
import cc.ivera.magnet.engine.tracker.TrackerAddressPolicy;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class NativeParallelBitfieldProbeTest {
    @Test void fourBitfieldsProbeConcurrentlyWithoutMixingPeerResults() throws Exception {
        var arrived=new CountDownLatch(4);
        var concurrent=new AtomicInteger();
        var peak=new AtomicInteger();
        var torrent=new TorrentInfo("b".repeat(40),"probe",
                List.of(new PieceLayout.FileEntry(0,"probe.bin",0,1)),1,1,new byte[20]);
        var worker=new NativeTaskWorkerRoundService(
                mock(NativeTaskLifecycleService.class),
                mock(NativeTaskCheckpointFactory.class),
                new NativeTorrentProperties(),
                (hash,peerId,port,permit)->List.of(),
                (peer,info,peerId,planner,index,timeout)->new byte[]{0},
                new TrackerAddressPolicy(true),
                PieceDownloadCoordinator.ChildThreads.none(),
                (peer,info,id,timeout)->{
                    int now=concurrent.incrementAndGet();
                    peak.accumulateAndGet(now,Math::max);
                    try {
                        arrived.countDown();
                        if(!arrived.await(1500,TimeUnit.MILLISECONDS))
                            return Optional.empty();
                        var bitfield=new BitSet();
                        bitfield.set(0);
                        return Optional.of(bitfield);
                    }catch(InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return Optional.empty();
                    }finally {concurrent.decrementAndGet();}
                });
        List<InetSocketAddress> peers=new ArrayList<>();
        for(int i=0;i<4;i++)
            peers.add(InetSocketAddress.createUnresolved("192.0.2."+(i+1),6881));
        var reported=worker.parallelBitfieldProbe(torrent,new byte[20],peers,4,1800);
        assertEquals(4,peak.get(),"Four probes must overlap, not run serially");
        assertEquals(4,reported.reports());
        for(var peer:peers)assertTrue(reported.confirmed(peer,0));
    }

    @Test void peerFailuresLeaveUnknownNotKnownAbsent() throws Exception {
        var torrent=new TorrentInfo("b".repeat(40),"probe",
                List.of(new PieceLayout.FileEntry(0,"probe.bin",0,1)),1,1,new byte[20]);
        var worker=new NativeTaskWorkerRoundService(
                mock(NativeTaskLifecycleService.class),
                mock(NativeTaskCheckpointFactory.class),new NativeTorrentProperties(),
                (hash,id,port,permit)->List.of(),
                (peer,info,id,planner,index,timeout)->new byte[]{0},
                new TrackerAddressPolicy(true),
                PieceDownloadCoordinator.ChildThreads.none(),
                (peer,info,id,timeout)->{throw new java.io.IOException("Peer unavailable");});
        var peer=InetSocketAddress.createUnresolved("192.0.2.15",6881);
        var availability=worker.parallelBitfieldProbe(torrent,new byte[20],List.of(peer),1,500);
        assertEquals(0,availability.reports());
        assertTrue(availability.couldHave(peer,0),"Unknown cannot be marked unavailable");
    }
}
