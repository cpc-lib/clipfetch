package cc.ivera.magnet.engine.piece;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PeerBlockMailboxTest {
    private static java.util.Set<?> activePrimary(PeerBlockMailbox mailbox)
            throws ReflectiveOperationException {
        var field=PeerBlockMailbox.class.getDeclaredField("activePrimary");
        field.setAccessible(true);
        return (java.util.Set<?>)field.get(mailbox);
    }

    @Test
    void remainingPiecesReturnsStableImmutableSnapshot() {
        var first=new PeerBlockMailbox.Key(3,0,PieceBlockPlanner.BLOCK_BYTES);
        var second=new PeerBlockMailbox.Key(1,0,PieceBlockPlanner.BLOCK_BYTES);
        var mailbox=new PeerBlockMailbox(List.of(first,second));

        var snapshot=mailbox.remainingPieces();

        assertEquals(List.of(3,1),new ArrayList<>(snapshot));
        assertSame(snapshot,mailbox.remainingPieces());
        assertThrows(UnsupportedOperationException.class,snapshot::clear);
    }

    @Test
    void remainingPiecesRefreshesOnlyAfterPieceCompletes() throws Exception {
        var peer=new InetSocketAddress("192.0.2.30",6881);
        var first=new PeerBlockMailbox.Key(0,0,PieceBlockPlanner.BLOCK_BYTES);
        var second=new PeerBlockMailbox.Key(0,PieceBlockPlanner.BLOCK_BYTES,
                PieceBlockPlanner.BLOCK_BYTES);
        var third=new PeerBlockMailbox.Key(1,0,PieceBlockPlanner.BLOCK_BYTES);
        var mailbox=new PeerBlockMailbox(List.of(first,second,third));
        var availability=new PeerPieceAvailability(2,Map.of());

        assertEquals(3,mailbox.claim(peer,availability,0,16).size());
        var initial=mailbox.remainingPieces();

        mailbox.deliver(peer,first,new byte[first.length()]);
        assertEquals(List.of(0,1),new ArrayList<>(mailbox.remainingPieces()));
        assertSame(initial,mailbox.remainingPieces());

        mailbox.deliver(peer,second,new byte[second.length()]);
        var afterPiece=mailbox.remainingPieces();
        assertEquals(List.of(0,1),new ArrayList<>(initial));
        assertEquals(List.of(1),new ArrayList<>(afterPiece));
        assertNotSame(initial,afterPiece);

        mailbox.deliver(peer,third,new byte[third.length()]);
        assertTrue(mailbox.remainingPieces().isEmpty());
    }

    @Test
    void claimChecksKnownUnavailablePieceOncePerPieceBucket() {
        var peer=new InetSocketAddress("192.0.2.12",6881);
        int pieces=128;
        int blocksPerPiece=PeerBlockMailbox.MAX_BLOCKS/pieces;
        var keys=new ArrayList<PeerBlockMailbox.Key>(PeerBlockMailbox.MAX_BLOCKS);
        for(int piece=0;piece<pieces;piece++)
            for(int block=0;block<blocksPerPiece;block++)
                keys.add(new PeerBlockMailbox.Key(piece,
                        block*PieceBlockPlanner.BLOCK_BYTES,
                        PieceBlockPlanner.BLOCK_BYTES));

        var emptyBitfield=new CountingBitSet();
        var availability=new PeerPieceAvailability(pieces,Map.of(peer,emptyBitfield));
        var mailbox=new PeerBlockMailbox(keys,128);

        assertTrue(mailbox.claim(peer,availability,0,128).isEmpty());
        assertEquals(pieces,emptyBitfield.getCalls());
    }

    private static final class CountingBitSet extends BitSet {
        private int getCalls;

        @Override public boolean get(int bitIndex) {
            getCalls++;
            return super.get(bitIndex);
        }

        @Override public Object clone() { return this; }

        int getCalls() { return getCalls; }
    }

    @Test
    void waitingPeerWakesWhenDisconnectedPeerReleasesBlocks() throws Exception {
        var firstPeer=new InetSocketAddress("192.0.2.10",6881);
        var waitingPeer=new InetSocketAddress("192.0.2.11",6881);
        var availability=new PeerPieceAvailability(1,Map.of());
        var mailbox=new PeerBlockMailbox(List.of(
                new PeerBlockMailbox.Key(0,0,PieceBlockPlanner.BLOCK_BYTES),
                new PeerBlockMailbox.Key(0,PieceBlockPlanner.BLOCK_BYTES,
                        PieceBlockPlanner.BLOCK_BYTES)),16);

        assertEquals(2,mailbox.claim(firstPeer,availability,0,16).size());

        var started=new CountDownLatch(1);
        var completed=new CountDownLatch(1);
        var failure=new AtomicReference<Throwable>();
        var waiter=new Thread(()->{
            started.countDown();
            try {
                mailbox.awaitStateChange(waitingPeer,Duration.ofMillis(250).toNanos());
            }catch(Throwable error) {
                failure.set(error);
            }finally {
                completed.countDown();
            }
        },"peer-mailbox-waiter");

        waiter.start();
        assertTrue(started.await(1,TimeUnit.SECONDS));
        TimeUnit.MILLISECONDS.sleep(40);
        mailbox.disconnect(firstPeer);

        try {
            assertTrue(completed.await(200,TimeUnit.MILLISECONDS));
        }finally {
            waiter.interrupt();
            waiter.join(1_000);
        }

        assertNull(failure.get());
        assertEquals(2,mailbox.claim(waitingPeer,availability,System.nanoTime(),16).size());
    }

    @Test
    void activePrimaryIndexTracksFreshCompletionAndDisconnect() throws Exception {
        var firstPeer=new InetSocketAddress("192.0.2.20",6881);
        var secondPeer=new InetSocketAddress("192.0.2.21",6881);
        var availability=new PeerPieceAvailability(1,Map.of());
        var firstKey=new PeerBlockMailbox.Key(0,0,PieceBlockPlanner.BLOCK_BYTES);
        var secondKey=new PeerBlockMailbox.Key(0,PieceBlockPlanner.BLOCK_BYTES,
                PieceBlockPlanner.BLOCK_BYTES);
        var mailbox=new PeerBlockMailbox(List.of(firstKey,secondKey),16);

        assertEquals(0,activePrimary(mailbox).size());
        assertEquals(2,mailbox.claim(firstPeer,availability,0,16).size());
        assertEquals(2,activePrimary(mailbox).size());

        mailbox.deliver(firstPeer,firstKey,new byte[firstKey.length()]);
        assertEquals(1,activePrimary(mailbox).size());

        mailbox.disconnect(firstPeer);
        assertEquals(0,activePrimary(mailbox).size());
        assertEquals(1,mailbox.claim(secondPeer,availability,1,16).size());
        assertEquals(1,activePrimary(mailbox).size());
    }

    @Test
    void primaryDisconnectPromotesBackupWithoutRemovingActiveIndex() throws Exception {
        var primary=new InetSocketAddress("192.0.2.22",6881);
        var backup=new InetSocketAddress("192.0.2.23",6881);
        var thirdPeer=new InetSocketAddress("192.0.2.24",6881);
        var availability=new PeerPieceAvailability(1,Map.of());
        var key=new PeerBlockMailbox.Key(0,0,PieceBlockPlanner.BLOCK_BYTES);
        var mailbox=new PeerBlockMailbox(List.of(key),16);

        assertEquals(1,mailbox.claim(primary,availability,0,16).size());
        assertEquals(1,mailbox.claim(backup,availability,2_000_000_000L,16).size());
        assertEquals(1,activePrimary(mailbox).size());

        mailbox.disconnect(primary);
        assertEquals(1,activePrimary(mailbox).size());

        mailbox.disconnect(backup);
        assertEquals(0,activePrimary(mailbox).size());
        assertEquals(1,mailbox.claim(thirdPeer,availability,3_000_000_000L,16).size());
        assertEquals(1,activePrimary(mailbox).size());
    }

    @Test
    void overdueReassignmentKeepsTransferredBlockInActiveIndex() throws Exception {
        var primary=new InetSocketAddress("192.0.2.25",6881);
        var receiver=new InetSocketAddress("192.0.2.26",6881);
        var availability=new PeerPieceAvailability(1,Map.of());
        var keys=new ArrayList<PeerBlockMailbox.Key>();
        for(int block=0;block<17;block++)
            keys.add(new PeerBlockMailbox.Key(0,
                    block*PieceBlockPlanner.BLOCK_BYTES,
                    PieceBlockPlanner.BLOCK_BYTES));
        var mailbox=new PeerBlockMailbox(keys,16);

        assertEquals(16,mailbox.claim(primary,availability,0,16).size());
        assertEquals(16,activePrimary(mailbox).size());
        assertEquals(1,mailbox.reassignOverdue(receiver,availability,
                12_000_000_000L,1).size());
        assertEquals(16,activePrimary(mailbox).size());
    }
}
