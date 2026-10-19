package cc.ivera.magnet.engine.piece;

import cc.ivera.magnet.engine.config.NativeTorrentProperties;
import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * P4 guarded inbound TCP BitTorrent peer acceptor. Never starts by default.
 * An inbound connection is accepted only when the active task has provided
 * a verified public-swarm InfoHash; the task's connection pool performs the
 * full handshake, scope check and adoption.
 *
 * Does not alter firewall, router configuration, UPnP or NAT mappings.
 */
public final class NativeInboundPeerServer implements AutoCloseable {
    private final PeerPieceDownloadSession session;
    private final NativeTorrentProperties settings;
    private final AtomicInteger admitted=new AtomicInteger();
    private final AtomicInteger rejected=new AtomicInteger();
    private volatile ServerSocket listener;
    private volatile Thread acceptor;
    private volatile ThreadPoolExecutor handshakes;
    public NativeInboundPeerServer(PeerPieceDownloadSession session,
                                   NativeTorrentProperties settings) {
        if(session==null || settings==null)throw new IllegalArgumentException("Missing inbound config");
        this.session=session;
        this.settings=settings;
    }
    public synchronized void start() throws IOException {
        if(!settings.isInboundTcpEnabled())return;
        if(listener!=null)throw new IllegalStateException("Inbound TCP is already listening");
        if(!settings.isEnabled() || !settings.isWorkerRoundEnabled()
                || !settings.isPeerConnectionReuseEnabled())
            throw new IllegalStateException("Inbound TCP requires enabled, reusable native engine");
        String host=settings.getInboundBindHost();
        int port=settings.getInboundListenPort();
        if(host==null || port<1 || port>65535)
            throw new IllegalArgumentException("Invalid inbound listen address");
        if(!settings.isInboundPublicExposureEnabled() &&
                !("127.0.0.1".equals(host) || "::1".equals(host)))
            throw new IllegalArgumentException("Non-loopback binding needs explicit exposure opt-in");
        if(!("127.0.0.1".equals(host) || "::1".equals(host)
                || "0.0.0.0".equals(host) || "::".equals(host)))
            throw new IllegalArgumentException("Only literal interface addresses are accepted");
        var socket=new ServerSocket();
        socket.setReuseAddress(false);
        socket.bind(new InetSocketAddress(InetAddress.getByName(host),port),32);
        socket.setSoTimeout(500);
        handshakes=new ThreadPoolExecutor(2,8,30,TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(24),r->{
                    Thread worker=new Thread(r,"clipfetch-inbound-peer-handshake");
                    worker.setDaemon(true);
                    return worker;
                },new ThreadPoolExecutor.AbortPolicy());
        listener=socket;
        Thread server=new Thread(()->acceptLoop(socket),"clipfetch-inbound-peer-accept");
        server.setDaemon(true);
        acceptor=server;
        server.start();
    }
    private void acceptLoop(ServerSocket socket) {
        while(!socket.isClosed()) {
            Socket inbound=null;
            try {
                inbound=socket.accept();
                final Socket accepted=inbound;
                ThreadPoolExecutor handlers=handshakes;
                if(handlers==null)throw new RejectedExecutionException("Inbound server is stopping");
                handlers.execute(()->{
                    try {session.acceptInbound(accepted);admitted.incrementAndGet();}
                    catch(IOException | RuntimeException invalid) {
                        rejected.incrementAndGet();
                        try {accepted.close();}catch(IOException ignored){}
                    }
                });
                inbound=null;
            }catch(SocketTimeoutException ignored) {
                // Re-check close/disable promptly.
            }catch(IOException | RejectedExecutionException fail) {
                if(inbound!=null) {
                    rejected.incrementAndGet();
                    try {inbound.close();}catch(IOException ignored){}
                }
                if(socket.isClosed())break;
            }
        }
    }
    public int listeningPort() {var s=listener;return s==null?0:s.getLocalPort();}
    public int acceptedCount(){return admitted.get();}
    public int rejectedCount(){return rejected.get();}
    @Override public synchronized void close() {
        var active=listener;
        listener=null;
        if(active!=null)try {active.close();}catch(IOException ignored){}
        var pool=handshakes;
        handshakes=null;
        if(pool!=null)pool.shutdownNow();
        var thread=acceptor;
        acceptor=null;
        if(thread!=null)thread.interrupt();
    }
}
