package cc.ivera.magnet.engine.piece;

import cc.ivera.magnet.engine.peer.PeerWireCodec;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;

/**
 * Actor-confined bounded BEP3 reader. It never abandons a partially read
 * TCP frame on a short socket timeout, which would desynchronize all later
 * Block responses. Preserves partial header and payload across calls.
 */
final class PeerIncrementalFrameReader {
    private static final int MAX_FRAME=64*1024;
    private final PeerWireCodec codec=new PeerWireCodec();
    private final byte[] header=new byte[4];
    private int headerRead;
    private byte[] body;
    private int bodyRead;
    private long lastProgressNanos;
    private Socket lastSocket;
    private int currentTimeoutMs = -1;

    PeerWireCodec.Message poll(InputStream input) throws IOException {
        if(input==null)throw new IllegalArgumentException("Missing Peer stream");
        while(input.available()>0) {
            if(headerRead<4) {
                int available=Math.min(input.available(),4-headerRead);
                int n=input.read(header,headerRead,available);
                if(n<0)throw new IOException("Peer disconnected during frame header");
                if(n==0)return null;
                headerRead+=n;
                lastProgressNanos=System.nanoTime();
                if(headerRead<4)continue;
                int length=ByteBuffer.wrap(header).getInt();
                if(length<0 || length>MAX_FRAME)
                    throw new IOException("Peer frame exceeds 64 KiB cap");
                body=new byte[length];
            }
            if(bodyRead<body.length) {
                int amount=Math.min(input.available(),body.length-bodyRead);
                if(amount==0)return null;
                int n=input.read(body,bodyRead,amount);
                if(n<0)throw new IOException("Peer disconnected during frame payload");
                if(n==0)return null;
                bodyRead+=n;
                lastProgressNanos=System.nanoTime();
                if(bodyRead<body.length)continue;
            }
            return assemble();
        }
        return null;
    }

    PeerWireCodec.Message readFrame(InputStream input, Socket socket, int timeoutMs) throws IOException {
        if(input==null)throw new IllegalArgumentException("Missing Peer stream");
        // 1. Check if a complete frame can be read from already-buffered stream bytes
        PeerWireCodec.Message cached=poll(input);
        if(cached!=null)return cached;
        if(socket==null || socket.isClosed())return null;
        int targetTimeout = Math.max(1, timeoutMs);
        if (socket != lastSocket || currentTimeoutMs != targetTimeout) {
            socket.setSoTimeout(targetTimeout);
            lastSocket = socket;
            currentTimeoutMs = targetTimeout;
        }
        try {
            while(headerRead<4) {
                int n=input.read(header,headerRead,4-headerRead);
                if(n<0)throw new IOException("Peer disconnected during frame header");
                if(n==0)break;
                headerRead+=n;
                lastProgressNanos=System.nanoTime();
            }
            if(headerRead==4 && body==null) {
                int length=ByteBuffer.wrap(header).getInt();
                if(length<0 || length>MAX_FRAME)
                    throw new IOException("Peer frame exceeds 64 KiB cap");
                body=new byte[length];
                bodyRead=0;
            }
            if(body!=null) {
                while(bodyRead<body.length) {
                    int n=input.read(body,bodyRead,body.length-bodyRead);
                    if(n<0)throw new IOException("Peer disconnected during frame payload");
                    if(n==0)break;
                    bodyRead+=n;
                    lastProgressNanos=System.nanoTime();
                }
                if(bodyRead==body.length) {
                    return assemble();
                }
            }
            return null;
        }catch(SocketTimeoutException timeout) {
            return null;
        }
    }

    private PeerWireCodec.Message assemble() throws IOException {
        var frame=ByteBuffer.allocate(4+body.length);
        frame.put(header).put(body).flip();
        final PeerWireCodec.Message parsed;
        try {
            parsed=codec.decodeMessage(frame,MAX_FRAME).orElseThrow();
        }catch(IllegalArgumentException invalid) {
            throw new IOException("Invalid incremental Peer wire message",invalid);
        }
        headerRead=0;
        bodyRead=0;
        body=null;
        lastProgressNanos=0;
        return parsed;
    }

    boolean hasPartialFrame(){return headerRead>0;}
    long lastProgressNanos(){return lastProgressNanos;}
}
