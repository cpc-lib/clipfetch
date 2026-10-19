package cc.ivera.magnet.engine.utp;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.*;

/**
 * A bounded, task-scoped uTP UDP connection-ID registry.
 * Does not accept unauthenticated inbound SYNs or bind public sockets.
 * The application must authorize and pin owner/task/endpoint first.
 */
public final class UtpConnectionRegistry {
    private static final int MAX_PER_TASK=64;
    private record Key(long owner,UUID task,InetSocketAddress endpoint,int receiveId) {}
    private final Map<Key,UtpHandshakeState> sessions=new HashMap<>();
    public synchronized void register(long owner,UUID task,UtpHandshakeState state)
            throws IOException {
        if(owner<=0 || task==null || state==null ||
                state.phase()==UtpHandshakeState.Phase.CLOSED)
            throw new IOException("Invalid task-scoped uTP session");
        int active=0;
        for(var key:sessions.keySet())
            if(key.owner==owner && key.task.equals(task))active++;
        if(active>=MAX_PER_TASK)throw new IOException("uTP task session limit exceeded");
        var key=new Key(owner,task,state.endpoint(),state.receiveId());
        if(sessions.putIfAbsent(key,state)!=null)
            throw new IOException("Duplicate uTP address and connection ID");
    }
    public synchronized UtpHandshakeState route(long owner,UUID task,
                                                InetSocketAddress endpoint,
                                                UtpPacketCodec.Packet incoming)
            throws IOException {
        if(incoming==null || endpoint==null || task==null || owner<=0)
            throw new IOException("Invalid uTP datagram route");
        var session=sessions.get(new Key(owner,task,endpoint,incoming.connectionId()));
        if(session==null)throw new IOException("Unknown task-scoped uTP session");
        session.onInbound(endpoint,incoming);
        return session;
    }
    public synchronized int taskCount(long owner,UUID task) {
        int count=0;
        for(var key:sessions.keySet())
            if(key.owner==owner && key.task.equals(task))count++;
        return count;
    }
    public synchronized void removeTask(long owner,UUID task) {
        var iterator=sessions.entrySet().iterator();
        while(iterator.hasNext()) {
            var entry=iterator.next();
            if(entry.getKey().owner==owner && entry.getKey().task.equals(task)) {
                entry.getValue().close();
                iterator.remove();
            }
        }
    }
}
