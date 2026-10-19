package cc.ivera.magnet.engine.piece;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AdaptivePeerConcurrencyTest {
    @Test void rampsUpToRealEligiblePeersWithoutExceedingMemoryOrConfiguredLimits() {
        var controller=new AdaptivePeerConcurrency();
        assertEquals(8,controller.slots(64,48,64,64));
        controller.observe(8,8);
        assertEquals(16,controller.slots(64,48,64,64));
        controller.observe(16,14);
        assertEquals(32,controller.slots(64,48,64,64));
        controller.observe(32,30);
        assertEquals(48,controller.slots(64,48,64,64));
        assertEquals(6,controller.slots(64,6,64,64));
        assertEquals(12,controller.slots(64,48,64,12));
        assertEquals(3,controller.slots(64,48,3,64));
        assertEquals(64,controller.slots(64,100,64,64));
        controller.observe(64,64);
        assertEquals(128,controller.slots(128,200,128,128));
        assertEquals(16,controller.slots(128,200,128,16));
    }

    @Test void setbacksAndRecoveryCannotExceedResourceLimits() {
        var state=new AdaptivePeerConcurrency();
        state.observe(8,8);
        state.observe(16,16);
        state.observe(32,0);
        assertEquals(16,state.currentTarget());
        state.observe(16,3);
        assertEquals(8,state.currentTarget());
        state.observe(8,4);
        assertEquals(16,state.currentTarget());
        var carried=new AdaptivePeerConcurrency(state);
        assertEquals(16,carried.currentTarget());
        assertThrows(IllegalArgumentException.class,()->carried.observe(1,2));
    }

    @Test void multipleBadRoundsCannotCollapseThirtyHealthyCandidatesToOneSocket() {
        var state=new AdaptivePeerConcurrency();
        for(int i=0;i<9;i++)state.observe(128,0);
        assertEquals(8,state.currentTarget(),"Failed rounds must retain a bounded exploration floor");
        assertEquals(8,state.slots(128,30,128,127));
        assertEquals(3,state.slots(128,3,128,127),
                "Do not create more connections than presently eligible peers");
        state.observe(8,7);
        assertEquals(16,state.slots(128,30,128,127),
                "Real verified successes must grow the useful window again");
    }

    @Test void noEligiblePeerRetainsOnlyDiscoverySlot() {
        var state=new AdaptivePeerConcurrency();
        assertEquals(1,state.slots(64,0,64,64));
        assertEquals(1,state.slots(64,30,0,64));
    }
}
