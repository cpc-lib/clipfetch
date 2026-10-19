package cc.ivera.magnet.engine.utp;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.net.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class UtpHandshakeStateTest {
    private static InetSocketAddress remote(int port) throws Exception {
        return new InetSocketAddress(InetAddress.getLoopbackAddress(),port);
    }
    private static UtpPacketCodec.Packet packet(int type,int id,int seq,int ack) {
        return new UtpPacketCodec.Packet(type,id,10,0,1024,seq,ack,
                new byte[0],type==UtpPacketCodec.DATA?new byte[]{1}:new byte[0]);
    }
    @Test void bidirectionalConnectionIdsAndSynStateHandshakeMatchBep29() throws Exception {
        var peer=remote(6881);
        var client=UtpHandshakeState.initiate(peer,65535,1_000_000);
        assertEquals(UtpPacketCodec.SYN,client.initialPacket().type());
        assertEquals(65535,client.initialPacket().connectionId());
        assertEquals(0,client.state().sendId(),"ID+1 wraps to zero");
        var server=UtpHandshakeState.accept(peer,client.initialPacket(),1234,2_000_000);
        assertEquals(0,server.state().receiveId());
        assertEquals(65535,server.state().sendId());
        assertEquals(UtpPacketCodec.STATE,server.initialPacket().type());
        assertEquals(65535,server.initialPacket().connectionId());
        assertEquals(1,server.initialPacket().ackNr());
        client.state().onInbound(peer,server.initialPacket());
        assertEquals(UtpHandshakeState.Phase.CONNECTED,client.state().phase());
        assertEquals(2,client.state().nextDataSequence());
        assertEquals(1235,client.state().nextExpectedReceiveSequence());
        assertEquals(UtpHandshakeState.Phase.SYN_RECEIVED,server.state().phase());
        var firstData=packet(UtpPacketCodec.DATA,0,2,1234);
        server.state().onInbound(peer,firstData);
        assertEquals(UtpHandshakeState.Phase.CONNECTED,server.state().phase());
        assertEquals(3,server.state().nextExpectedReceiveSequence());
    }
    @Test void wrongEndpointAndForgedSynResponseAreRejected() throws Exception {
        var peer=remote(6112);
        var client=UtpHandshakeState.initiate(peer,23,100);
        assertThrows(IOException.class,()->client.state().onInbound(
                remote(6113),packet(UtpPacketCodec.STATE,23,20,1)));
        assertThrows(IOException.class,()->client.state().onInbound(
                peer,packet(UtpPacketCodec.STATE,23,20,999)));
        assertThrows(IOException.class,()->client.state().onInbound(
                peer,packet(UtpPacketCodec.STATE,24,20,1)));
        assertEquals(UtpHandshakeState.Phase.SYN_SENT,client.state().phase());
        client.state().onInbound(peer,packet(UtpPacketCodec.STATE,23,20,1));
        assertEquals(UtpHandshakeState.Phase.CONNECTED,client.state().phase());
    }
    @Test void registryRejectsCrossTaskRoutingAndDuplicateIds() throws Exception {
        var peer=remote(6123);
        var client=UtpHandshakeState.initiate(peer,13,500);
        var registry=new UtpConnectionRegistry();
        var taskA=UUID.randomUUID();
        var taskB=UUID.randomUUID();
        registry.register(7,taskA,client.state());
        assertThrows(IOException.class,()->registry.register(7,taskA,client.state()));
        var response=packet(UtpPacketCodec.STATE,13,40,1);
        assertThrows(IOException.class,()->registry.route(8,taskA,peer,response));
        assertThrows(IOException.class,()->registry.route(7,taskB,peer,response));
        assertThrows(IOException.class,()->registry.route(7,taskA,remote(6124),response));
        registry.route(7,taskA,peer,response);
        assertEquals(1,registry.taskCount(7,taskA));
        registry.removeTask(7,taskA);
        assertEquals(0,registry.taskCount(7,taskA));
        assertEquals(UtpHandshakeState.Phase.CLOSED,client.state().phase());
        assertThrows(IOException.class,()->registry.route(7,taskA,peer,response));
    }
}
