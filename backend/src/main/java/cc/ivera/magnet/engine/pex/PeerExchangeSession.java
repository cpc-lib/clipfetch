package cc.ivera.magnet.engine.pex;

import cc.ivera.magnet.engine.codec.BencodeDecoder;
import cc.ivera.magnet.engine.codec.BencodeDecoder.*;
import cc.ivera.magnet.engine.codec.BencodeEncoder;
import cc.ivera.magnet.engine.codec.ByteString;
import cc.ivera.magnet.engine.peer.PeerWireCodec;
import cc.ivera.magnet.engine.tracker.TrackerAddressPolicy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Arrays;
import cc.ivera.magnet.engine.piece.NativeNetworkBudget;
import java.util.Map;

/** BEP10/BEP11 opt-in one-shot PEX collection from an already discovered Peer. */
public final class PeerExchangeSession {
    private final TrackerAddressPolicy policy;
    private final PeerWireCodec wire=new PeerWireCodec();
    private final BencodeDecoder decoder=new BencodeDecoder();
    private final BencodeEncoder encoder=new BencodeEncoder();
    private final PeerExchangeCodec pex=new PeerExchangeCodec();

    public PeerExchangeSession(TrackerAddressPolicy policy) { this.policy=policy; }
    public PeerExchangeCodec.Delta collect(InetSocketAddress source,byte[] infoHash,
                                           byte[] localPeerId,Duration timeout,
                                           boolean publicTorrentAuthorized) throws IOException {
        if (!publicTorrentAuthorized) throw new SecurityException("PEX is disabled for private or unverified swarms");
        if (source==null || source.getPort()<1 || source.getPort()>65535
                || infoHash==null || infoHash.length!=20
                || localPeerId==null || localPeerId.length!=20
                || timeout==null || timeout.isZero() || timeout.isNegative()
                || timeout.compareTo(Duration.ofSeconds(8))>0)
            throw new IllegalArgumentException("Invalid PEX session");
        timeout=NativeNetworkBudget.cap(timeout);
        long deadline=System.nanoTime()+timeout.toNanos();
        var pinned=new InetSocketAddress(policy.resolve(source.getHostString()),source.getPort());
        try (Socket socket=new Socket()) {
            socket.connect(pinned,remaining(deadline));
            socket.setSoTimeout(remaining(deadline));
            var in=socket.getInputStream();var out=socket.getOutputStream();
            byte[] reserved=new byte[8]; reserved[5]=0x10;
            out.write(wire.encodeHandshake(new PeerWireCodec.Handshake(reserved,infoHash,localPeerId)).array());
            out.flush();
            byte[] response=read(in,socket,PeerWireCodec.HANDSHAKE_LENGTH,deadline);
            PeerWireCodec.Handshake remote;
            try { remote=wire.decodeHandshake(ByteBuffer.wrap(response)).orElseThrow(); }
            catch (RuntimeException e) { throw new IOException("Invalid PEX handshake",e); }
            if (!Arrays.equals(remote.infoHash(),infoHash) || Arrays.equals(remote.peerId(),localPeerId)
                    || (remote.reserved()[5]&0x10)==0)
                throw new IOException("Peer does not support this swarm's extensions");
            byte[] handshake=encoder.encode(new DictionaryValue(Map.of(
                    ByteString.ascii("m"),new DictionaryValue(Map.of(
                            ByteString.ascii("ut_pex"),new IntegerValue(2))))));
            send(out,0,handshake,deadline);
            boolean supported=false;
            for(int n=0;n<32;n++) {
                ByteBuffer frame=ByteBuffer.wrap(read(in,socket,4,deadline));
                int length=frame.getInt();
                if (length<0 || length>32768) throw new IOException("Oversized Peer message");
                if(length==0)continue;
                byte[] body=read(in,socket,length,deadline);
                int type=body[0]&255;
                if(type!=20 || body.length<2)continue;
                int ext=body[1]&255;
                byte[] payload=Arrays.copyOfRange(body,2,body.length);
                if(ext==0) {
                    try {
                        if(!(decoder.decode(payload) instanceof DictionaryValue d)
                                || !(d.get("m") instanceof DictionaryValue m)
                                || !(m.get("ut_pex") instanceof IntegerValue v)
                                || v.value()<1 || v.value()>255)
                            throw new IOException("Remote Peer has no ut_pex");
                        supported=true;
                    } catch (IllegalArgumentException e) { throw new IOException("Invalid remote extension handshake",e); }
                } else if(ext==2 && supported) {
                    try { return pex.decode(payload); }
                    catch (IllegalArgumentException e) { throw new IOException("Malformed PEX delta",e); }
                }
            }
            throw new IOException("No PEX delta received");
        }
    }
    private void send(java.io.OutputStream out,int ext,byte[] data,long deadline) throws IOException {
        byte[] body=new byte[data.length+1];body[0]=(byte)ext;
        System.arraycopy(data,0,body,1,data.length);
        remaining(deadline);
        out.write(wire.encodeMessage(new PeerWireCodec.Message(20,body)).array());
        out.flush();remaining(deadline);
    }
    private static byte[] read(java.io.InputStream in,Socket socket,int count,long deadline) throws IOException {
        byte[] bytes=new byte[count];int at=0;
        while(at<count){socket.setSoTimeout(remaining(deadline));int n=in.read(bytes,at,count-at);
            if(n<0)throw new IOException("Peer disconnected during PEX");
            if(n>0)at+=n;}
        return bytes;
    }
    private static int remaining(long deadline) throws IOException {
        if(Thread.currentThread().isInterrupted())throw new IOException("PEX interrupted");
        long left=deadline-System.nanoTime();
        if(left<=0)throw new SocketTimeoutException("PEX deadline exceeded");
        return (int)Math.max(1,Math.min(Integer.MAX_VALUE,(left+999999)/1000000));
    }
}
