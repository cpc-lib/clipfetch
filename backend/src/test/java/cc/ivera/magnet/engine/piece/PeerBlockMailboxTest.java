package cc.ivera.magnet.engine.piece;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class PeerBlockMailboxTest {
    private static InetSocketAddress peer(int last) {
        return InetSocketAddress.createUnresolved("8.8.8."+last,6881);
    }
    private static PeerBlockMailbox.Key block(int piece,int slot) {
        return new PeerBlockMailbox.Key(piece,
                slot*PieceBlockPlanner.BLOCK_BYTES,PieceBlockPlanner.BLOCK_BYTES);
    }
    @Test void simultaneouslyLeasesDifferentPiecesByActualBitfield() throws Exception {
        var left=peer(1);var right=peer(2);
        var a=new BitSet();a.set(0);
        var b=new BitSet();b.set(1);
        var available=new PeerPieceAvailability(2,Map.of(left,a,right,b));
        var mailbox=new PeerBlockMailbox(List.of(block(0,0),block(1,0)));
        ExecutorService workers=Executors.newFixedThreadPool(2);
        try {
            var x=workers.submit(()->mailbox.claim(left,available,10,8));
            var y=workers.submit(()->mailbox.claim(right,available,10,8));
            var xa=x.get(1,TimeUnit.SECONDS);
            var ya=y.get(1,TimeUnit.SECONDS);
            assertEquals(0,xa.get(0).key().piece());
            assertEquals(1,ya.get(0).key().piece());
            assertFalse(xa.get(0).backup());
            assertEquals(1,mailbox.peerOutstanding(left));
            assertEquals(1,mailbox.peerOutstanding(right));
            assertEquals(PeerBlockMailbox.Delivery.ACCEPTED,
                    mailbox.deliver(left,xa.get(0).key(),new byte[16384]).delivery());
            assertEquals(PeerBlockMailbox.Delivery.ACCEPTED,
                    mailbox.deliver(right,ya.get(0).key(),new byte[16384]).delivery());
            assertEquals(0,mailbox.remaining());
            assertEquals(0,mailbox.peerOutstanding(left));
            assertEquals(0,mailbox.peerOutstanding(right));
        }finally {workers.shutdownNow();}
    }
    @Test void slowBlockCanGetOneBackupAndFirstResponseCancelsLoser() throws Exception {
        var a=peer(1);var b=peer(2);var c=peer(3);
        var available=new PeerPieceAvailability(1,Map.of());
        var key=block(0,0);
        var mailbox=new PeerBlockMailbox(List.of(key));
        assertEquals(1,mailbox.claim(a,available,100,1).size());
        assertEquals(0,mailbox.claim(b,available,1_000_000_000L,1).size());
        mailbox.observeRtt(a,300_000_000L);
        var backup=mailbox.claim(b,available,3_000_000_000L,1);
        assertEquals(1,backup.size());
        assertTrue(backup.get(0).backup());
        assertTrue(mailbox.claim(c,available,4_000_000_000L,1).isEmpty(),
                "At most one backup may race with the primary");
        var data=new byte[16384];data[0]=42;
        var accepted=mailbox.deliver(b,key,data);
        assertEquals(PeerBlockMailbox.Delivery.ACCEPTED,accepted.delivery());
        assertEquals(1,accepted.winner().cancel().size());
        assertEquals(a,accepted.winner().cancel().get(0).peer());
        assertEquals(0,mailbox.remaining());
        data[0]=13;
        assertEquals(42,accepted.winner().payload()[0],"Winner payload is defensively copied");
        assertEquals(PeerBlockMailbox.Delivery.DUPLICATE,
                mailbox.deliver(a,key,new byte[16384]).delivery());
        assertEquals(0,mailbox.peerOutstanding(a));
        assertEquals(0,mailbox.peerOutstanding(b));
        assertThrows(IOException.class,()->mailbox.deliver(c,block(0,1),new byte[16384]));
    }
    @Test void failedPeerReleasesLeaseAndNeverClaimsAHashUnverifiedResult() throws Exception {
        var left=peer(1);var right=peer(2);
        var mailbox=new PeerBlockMailbox(List.of(block(0,0),block(0,1)));
        var available=new PeerPieceAvailability(1,Map.of());
        assertEquals(2,mailbox.claim(left,available,5,8).size());
        mailbox.disconnect(left);
        assertEquals(0,mailbox.peerOutstanding(left));
        assertEquals(2,mailbox.remaining());
        assertEquals(2,mailbox.claim(right,available,10,8).size());
        assertThrows(IOException.class,()->mailbox.deliver(left,block(0,0),new byte[16384]));
        for(int n=0;n<2;n++)mailbox.deliver(right,block(0,n),new byte[16384]);
        assertEquals(0,mailbox.remaining());
    }
    @Test void queueTelemetryAccountsForUnclaimedPrimaryAndEndgameBackup() throws Exception {
        var a=peer(1);var b=peer(2);
        var key=block(0,0);
        var mailbox=new PeerBlockMailbox(List.of(key));
        var availability=new PeerPieceAvailability(1,Map.of());
        assertEquals(1,mailbox.queueStats().unclaimed());
        mailbox.claim(a,availability,1,1);
        assertEquals(0,mailbox.queueStats().unclaimed());
        assertEquals(1,mailbox.queueStats().primaryLeases());
        mailbox.claim(b,availability,2_100_000_000L,1);
        assertEquals(1,mailbox.queueStats().backupLeases());
        mailbox.deliver(b,key,new byte[16384]);
        assertEquals(1,mailbox.queueStats().completed());
        assertEquals(0,mailbox.queueStats().primaryLeases());
        assertEquals(0,mailbox.queueStats().backupLeases());
    }
    @Test void singleTcpPeerPrefetchesNextPieceBeforeCurrentPieceCompletes() {
        var keys=new ArrayList<PeerBlockMailbox.Key>();
        for(int p=0;p<2;p++)
            for(int i=0;i<20;i++)keys.add(block(p,i));
        var peer=peer(1);
        var mailbox=new PeerBlockMailbox(keys);
        var available=new PeerPieceAvailability(2,Map.of());
        var initial=mailbox.claim(peer,available,1,16);
        assertEquals(16,initial.size());
        assertEquals(12,initial.stream().filter(r->r.key().piece()==0).count());
        assertEquals(4,initial.stream().filter(r->r.key().piece()==1).count(),
                "Start next Piece on the same TCP connection without a Piece-boundary stall");
        assertEquals(16,mailbox.peerOutstanding(peer));
        assertEquals(0,mailbox.queueStats().completed());
        var second=mailbox.claim(peer,available,2,16);
        assertEquals(16,second.size());
        assertTrue(second.stream().anyMatch(r->r.key().piece()==1));
        assertEquals(32,mailbox.peerOutstanding(peer));
    }

    @Test void standbyActorRemainsEligibleForPendingLeasesAndPeerRecovery()
            throws Exception {
        var primary=peer(1);
        var standby=peer(2);
        var missing=peer(3);
        var onlyFirst=new BitSet();onlyFirst.set(0);
        var onlySecond=new BitSet();onlySecond.set(1);
        var bits=new PeerPieceAvailability(2,Map.of(
                primary,onlyFirst,standby,onlyFirst,missing,onlySecond));
        var key=block(0,0);
        var pending=block(1,0);
        var mailbox=new PeerBlockMailbox(List.of(key,pending));
        mailbox.claim(primary,bits,1,1);
        assertTrue(mailbox.hasPotentialWork(standby,bits),
                "Standby connection must not leave just because the primary owns the outstanding Block");
        assertFalse(mailbox.hasPotentialWork(missing,
                new PeerPieceAvailability(2,Map.of(missing,new BitSet()))));
        mailbox.disconnect(primary);
        assertEquals(1,mailbox.claim(standby,bits,2,1).size());
        mailbox.deliver(standby,key,new byte[16384]);
        assertFalse(mailbox.hasPotentialWork(standby,bits));
    }

    @Test void proactiveOverdueTransferBeforeEndgameReclaimsWithoutDoubleCrediting()
            throws Exception {
        var slow=peer(1);var fast=peer(2);
        var available=new PeerPieceAvailability(1,Map.of());
        var keys=new ArrayList<PeerBlockMailbox.Key>();
        for(int i=0;i<24;i++)keys.add(block(0,i));
        var mailbox=new PeerBlockMailbox(keys);
        assertEquals(24,mailbox.claim(slow,available,1,32).size());
        assertEquals(0,mailbox.queueStats().unclaimed());
        mailbox.observeRtt(slow,400_000_000L);
        assertTrue(mailbox.reassignOverdue(fast,available,
                1_999_999_999L,16).isEmpty(),
                "Do not preempt non-expired Block leases");
        var transferred=mailbox.reassignOverdue(fast,available,
                2_100_000_001L,16);
        assertEquals(8,transferred.size(),
                "Limit speculative transfer load to eight Blocks per tick");
        assertEquals(8,mailbox.peerOutstanding(fast));
        assertEquals(16,mailbox.peerOutstanding(slow));
        assertEquals(8,mailbox.timedOutTransfers());
        assertTrue(mailbox.reassignOverdue(slow,available,
                4_400_000_001L,8).isEmpty(),
                "A Peer whose request already timed out cannot steal healthy Peer leases back");
        assertTrue(mailbox.claim(slow,available,4_400_000_001L,8).isEmpty(),
                "Do not assign new blocks to an already retired timeout donor");
        assertTrue(mailbox.shouldRetire(slow),
                "A cancelled connection may contain stale Block responses");
        for(var handoff:transferred) {
            assertEquals(slow,handoff.cancel().peer());
            assertEquals(fast,handoff.request().peer());
            assertFalse(handoff.request().backup());
            assertEquals(PeerBlockMailbox.Delivery.DUPLICATE,
                    mailbox.deliver(slow,handoff.request().key(),new byte[16384]).delivery(),
                    "Late data is discarded before it reaches SHA-1 assembler");
        }
        assertEquals(8,mailbox.staleResponses());
        assertEquals(0,mailbox.queueStats().completed());
        // Previous slow endpoint cannot immediately reclaim a stolen Block.
        assertTrue(mailbox.claim(slow,available,2_200_000_001L,8).isEmpty());
        for(var handoff:transferred)
            assertEquals(PeerBlockMailbox.Delivery.ACCEPTED,
                    mailbox.deliver(fast,handoff.request().key(),new byte[16384]).delivery());
        assertEquals(8,mailbox.queueStats().completed());
        assertEquals(16,mailbox.remaining());
        assertEquals(0,mailbox.peerOutstanding(fast));
    }

    @Test void concurrentHealthyPeersNeverReceiveSameOverduePrimary()
            throws Exception {
        var stuck=peer(1);var a=peer(2);var b=peer(3);
        var available=new PeerPieceAvailability(1,Map.of());
        var keys=new ArrayList<PeerBlockMailbox.Key>();
        for(int i=0;i<24;i++)keys.add(block(0,i));
        var mailbox=new PeerBlockMailbox(keys);
        mailbox.claim(stuck,available,10,24);
        var workers=Executors.newFixedThreadPool(2);
        try {
            var first=workers.submit(()->mailbox.reassignOverdue(
                    a,available,12_100_000_010L,8));
            var second=workers.submit(()->mailbox.reassignOverdue(
                    b,available,12_100_000_010L,8));
            var both=new ArrayList<PeerBlockMailbox.Transfer>();
            both.addAll(first.get(2,TimeUnit.SECONDS));
            both.addAll(second.get(2,TimeUnit.SECONDS));
            assertEquals(16,both.size());
            assertEquals(16,both.stream().map(v->v.request().key()).distinct().count(),
                    "A Block cannot gain two new primary lease owners");
            assertEquals(8,mailbox.peerOutstanding(stuck));
            assertEquals(16,mailbox.timedOutTransfers());
        }finally {workers.shutdownNow();}
    }

    @Test void concurrentOverdueTransferAcrossPiecesKeepsOnePrimaryPerBlock()
            throws Exception {
        var slow=peer(1);var fastA=peer(2);var fastB=peer(3);
        var available=new PeerPieceAvailability(2,Map.of());
        var keys=new ArrayList<PeerBlockMailbox.Key>();
        for(int piece=0;piece<2;piece++)
            for(int i=0;i<24;i++)keys.add(block(piece,i));
        var mailbox=new PeerBlockMailbox(keys);
        assertEquals(48,mailbox.claim(slow,available,10,48).size());
        var pool=Executors.newFixedThreadPool(2);
        try {
            var a=pool.submit(()->mailbox.reassignOverdue(
                    fastA,available,12_100_000_010L,8));
            var b=pool.submit(()->mailbox.reassignOverdue(
                    fastB,available,12_100_000_010L,8));
            var transfers=new ArrayList<PeerBlockMailbox.Transfer>();
            transfers.addAll(a.get(2,TimeUnit.SECONDS));
            transfers.addAll(b.get(2,TimeUnit.SECONDS));
            assertEquals(16,transfers.size());
            assertEquals(16,transfers.stream().map(v->v.request().key()).distinct().count());
            assertTrue(mailbox.shouldRetire(slow));
            assertEquals(32,mailbox.peerOutstanding(slow));
            assertEquals(8,mailbox.peerOutstanding(fastA));
            assertEquals(8,mailbox.peerOutstanding(fastB));
            for(var transfer:transfers) {
                assertEquals(PeerBlockMailbox.Delivery.DUPLICATE,
                        mailbox.deliver(slow,transfer.request().key(),new byte[16384]).delivery());
                assertEquals(PeerBlockMailbox.Delivery.ACCEPTED,
                        mailbox.deliver(transfer.request().peer(),
                                transfer.request().key(),new byte[16384]).delivery());
            }
            assertEquals(16,mailbox.queueStats().completed());
            assertEquals(16,mailbox.staleResponses());
            assertEquals(32,mailbox.remaining());
        }finally {pool.shutdownNow();}
    }

    @Test void permanentlySilentDonorDisconnectReleasesAllRemainingLeasesWithoutDoubleCredit()
            throws Exception {
        var silent=peer(1);var healthy=peer(2);
        var available=new PeerPieceAvailability(2,Map.of());
        var keys=new ArrayList<PeerBlockMailbox.Key>();
        for(int piece=0;piece<2;piece++)
            for(int i=0;i<24;i++)keys.add(block(piece,i));
        var mailbox=new PeerBlockMailbox(keys);
        assertEquals(48,mailbox.claim(silent,available,10,48).size());
        var stolen=mailbox.reassignOverdue(healthy,available,12_100_000_010L,8);
        assertEquals(8,stolen.size());
        assertTrue(mailbox.shouldRetire(silent));
        for(var transfer:stolen) {
            assertEquals(PeerBlockMailbox.Delivery.DUPLICATE,
                    mailbox.deliver(silent,transfer.request().key(),new byte[16384]).delivery());
        }
        // The silent socket ultimately times out and is closed. The healthy
        // actor retains its eight transferred leases; the other forty become
        // available without ever double-counting a previously leased block.
        mailbox.disconnect(silent);
        assertEquals(40,mailbox.queueStats().unclaimed());
        assertEquals(8,mailbox.peerOutstanding(healthy));
        var remaining=mailbox.claim(healthy,available,12_200_000_010L,56);
        assertEquals(40,remaining.size());
        var received=new java.util.HashSet<PeerBlockMailbox.Key>();
        for(var transfer:stolen) {
            assertTrue(received.add(transfer.request().key()));
            assertEquals(PeerBlockMailbox.Delivery.ACCEPTED,
                    mailbox.deliver(healthy,transfer.request().key(),new byte[16384]).delivery());
        }
        for(var request:remaining) {
            assertTrue(received.add(request.key()));
            assertEquals(PeerBlockMailbox.Delivery.ACCEPTED,
                    mailbox.deliver(healthy,request.key(),new byte[16384]).delivery());
        }
        assertEquals(48,received.size());
        assertEquals(0,mailbox.remaining());
        assertEquals(48,mailbox.queueStats().completed());
        assertEquals(0,mailbox.peerOutstanding(healthy));
        assertFalse(mailbox.shouldRetire(healthy));
        assertTrue(mailbox.shouldRetire(silent));
    }

    @Test void eofDuringConcurrentTransferReleasesLeasesAndPreservesUniqueOwners()
            throws Exception {
        var failed=peer(1);var a=peer(2);var b=peer(3);
        var available=new PeerPieceAvailability(2,Map.of());
        var keys=new ArrayList<PeerBlockMailbox.Key>();
        for(int piece=0;piece<2;piece++)
            for(int slot=0;slot<24;slot++)keys.add(block(piece,slot));
        var mailbox=new PeerBlockMailbox(keys);
        assertEquals(48,mailbox.claim(failed,available,1,48).size());
        var workers=Executors.newFixedThreadPool(3);
        var gate=new CountDownLatch(1);
        try {
            var transferA=workers.submit(()->{gate.await();return mailbox.reassignOverdue(
                    a,available,2_100_000_001L,8);});
            var transferB=workers.submit(()->{gate.await();return mailbox.reassignOverdue(
                    b,available,2_100_000_001L,8);});
            var disconnect=workers.submit(()->{gate.await();mailbox.disconnect(failed);return true;});
            gate.countDown();
            var transferred=new ArrayList<PeerBlockMailbox.Transfer>();
            transferred.addAll(transferA.get(3,TimeUnit.SECONDS));
            transferred.addAll(transferB.get(3,TimeUnit.SECONDS));
            assertTrue(disconnect.get(3,TimeUnit.SECONDS));
            assertEquals(transferred.size(),transferred.stream().map(
                    t->t.request().key()).distinct().count(),
                    "A released block cannot have two primary owners");
            var claimedA=mailbox.claim(a,available,2_200_000_001L,64);
            var claimedB=mailbox.claim(b,available,2_200_000_001L,64);
            var requests=new ArrayList<PeerBlockMailbox.Request>();
            for(var t:transferred)requests.add(t.request());
            requests.addAll(claimedA);
            requests.addAll(claimedB);
            assertEquals(48,requests.size(),
                    "EOF must never strand or duplicate leases during concurrent transfer");
            assertEquals(48,requests.stream().map(PeerBlockMailbox.Request::key).distinct().count());
            for(var request:requests)
                assertEquals(PeerBlockMailbox.Delivery.ACCEPTED,mailbox.deliver(
                        request.peer(),request.key(),new byte[16384]).delivery());
            assertEquals(48,mailbox.queueStats().completed());
            assertEquals(0,mailbox.remaining());
            assertEquals(0,mailbox.peerOutstanding(failed));
            assertEquals(0,mailbox.peerOutstanding(a));
            assertEquals(0,mailbox.peerOutstanding(b));
        }finally {workers.shutdownNow();}
    }

    @Test void endgameRaceRetiresBothSocketsButUnracedHealthySocketStaysReusable()
            throws Exception {
        var primary=peer(1);var backup=peer(2);var clean=peer(3);
        var available=new PeerPieceAvailability(1,Map.of());
        var mailbox=new PeerBlockMailbox(List.of(block(0,0),block(0,1)));
        var assigned=mailbox.claim(primary,available,10,2);
        assertEquals(2,assigned.size());
        var racing=mailbox.claim(backup,available,2_100_000_010L,1);
        assertEquals(1,racing.size());
        assertTrue(racing.get(0).backup());
        var winner=mailbox.deliver(backup,racing.get(0).key(),new byte[16384]);
        assertEquals(PeerBlockMailbox.Delivery.ACCEPTED,winner.delivery());
        assertEquals(1,winner.winner().cancel().size());
        assertTrue(mailbox.shouldRetire(primary),
                "A cancelled in-flight primary may deliver a stale TCP frame");
        assertTrue(mailbox.shouldRetire(backup),
                "Conservative endgame protection retires both racing sockets");
        assertFalse(mailbox.shouldRetire(clean),
                "An unrelated healthy actor must never be contaminated");
        assertEquals(PeerBlockMailbox.Delivery.DUPLICATE,
                mailbox.deliver(primary,racing.get(0).key(),new byte[16384]).delivery());
        mailbox.disconnect(primary);
        var recovered=mailbox.claim(clean,available,2_200_000_010L,1);
        assertEquals(1,recovered.size());
        assertEquals(PeerBlockMailbox.Delivery.ACCEPTED,
                mailbox.deliver(clean,recovered.get(0).key(),new byte[16384]).delivery());
        assertEquals(0,mailbox.remaining());
        assertFalse(mailbox.shouldRetire(clean));
    }

    @Test void uncertainDonorUsesExclusiveEndgameTransferNotHealthyBackup()
            throws Exception {
        var donor=peer(1);var healthy=peer(2);
        var available=new PeerPieceAvailability(1,Map.of());
        var keys=new ArrayList<PeerBlockMailbox.Key>();
        for(int i=0;i<24;i++)keys.add(block(0,i));
        var mailbox=new PeerBlockMailbox(keys);
        assertEquals(16,mailbox.claim(donor,available,1,16).size());
        assertEquals(8,mailbox.claim(healthy,available,2,8).size());
        var first=mailbox.reassignOverdue(healthy,available,12_100_000_010L,1);
        assertEquals(1,first.size());
        assertEquals(PeerBlockMailbox.Delivery.ACCEPTED,mailbox.deliver(
                healthy,first.get(0).request().key(),new byte[16384]).delivery());
        for(int slot=16;slot<24;slot++)
            assertEquals(PeerBlockMailbox.Delivery.ACCEPTED,mailbox.deliver(
                    healthy,block(0,slot),new byte[16384]).delivery());
        assertEquals(15,mailbox.remaining());
        assertTrue(mailbox.shouldRetire(donor));
        assertTrue(mailbox.claim(healthy,available,12_200_000_010L,8).isEmpty(),
                "Do not contaminate the healthy socket with a backup against retired donor");
        var exclusive=mailbox.reassignOverdue(healthy,available,12_200_000_010L,8);
        assertEquals(8,exclusive.size());
        assertTrue(exclusive.stream().allMatch(t->!t.request().backup()));
        assertEquals(0,mailbox.queueStats().backupLeases());
        for(var transfer:exclusive)
            assertEquals(PeerBlockMailbox.Delivery.ACCEPTED,mailbox.deliver(
                    healthy,transfer.request().key(),new byte[16384]).delivery());
        mailbox.disconnect(donor);
        var remaining=mailbox.claim(healthy,available,12_300_000_010L,16);
        for(var request:remaining)
            mailbox.deliver(healthy,request.key(),new byte[16384]);
        assertEquals(0,mailbox.remaining());
        assertFalse(mailbox.shouldRetire(healthy));
    }

    @Test void boundedWindowAndInvalidInputsRejected() {
        assertThrows(IllegalArgumentException.class,
                ()->new PeerBlockMailbox(List.of(block(0,0),block(0,0))));
        var items=new ArrayList<PeerBlockMailbox.Key>();
        for(int i=0;i<100;i++)items.add(block(0,i));
        var mailbox=new PeerBlockMailbox(items);
        var a=peer(1);
        assertEquals(64,mailbox.claim(a,new PeerPieceAvailability(1,Map.of()),5,64).size());
        assertTrue(mailbox.claim(a,new PeerPieceAvailability(1,Map.of()),6,64).isEmpty());
        assertEquals(64,mailbox.peerOutstanding(a));
    }

    @Test void closeForClaimingBlocksNewClaimsAndReopeningRestoresThem() {
        var mailbox=new PeerBlockMailbox(List.of(block(0,0),block(0,1)));
        var peer=peer(1);
        var available=new PeerPieceAvailability(1,Map.of());
        assertFalse(mailbox.closedForClaiming());

        mailbox.closeForClaiming();
        assertTrue(mailbox.closedForClaiming());
        assertTrue(mailbox.claim(peer,available,1,16).isEmpty(),
                "New claims must be rejected while closed for claiming");

        mailbox.reopenForClaiming();
        assertFalse(mailbox.closedForClaiming());
        assertEquals(2,mailbox.claim(peer,available,2,16).size(),
                "Reopening must permit claims to resume");
    }
}
