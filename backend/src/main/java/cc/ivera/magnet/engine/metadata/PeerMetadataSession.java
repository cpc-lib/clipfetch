package cc.ivera.magnet.engine.metadata;

import cc.ivera.magnet.engine.peer.PeerWireCodec;
import cc.ivera.magnet.engine.tracker.TrackerAddressPolicy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Arrays;

/**
 * One-shot BEP10/BEP9 metadata session with absolute monotonic transfer deadline.
 * Limited frame count, size and piece count; no file content is downloaded.
 */
public final class PeerMetadataSession {
    private final TrackerAddressPolicy policy;
    private final PeerWireCodec wire = new PeerWireCodec();
    private final MetadataExtensionCodec metadata = new MetadataExtensionCodec();

    public PeerMetadataSession(TrackerAddressPolicy policy) { this.policy = policy; }

    public byte[] fetch(InetSocketAddress endpoint, byte[] infoHash, byte[] peerId,
                        Duration timeout) throws IOException {
        if (endpoint == null || endpoint.getPort() < 1 || endpoint.getPort() > 65535
                || infoHash == null || infoHash.length != 20
                || peerId == null || peerId.length != 20 || timeout == null
                || timeout.isNegative() || timeout.isZero()
                || timeout.compareTo(Duration.ofSeconds(30)) > 0)
            throw new IllegalArgumentException("Invalid metadata session");
        long deadline = System.nanoTime() + timeout.toNanos();
        var pinned = new InetSocketAddress(policy.resolve(endpoint.getHostString()),endpoint.getPort());
        try (Socket socket = new Socket()) {
            // Do not spend an entire 8-second metadata attempt on dead TCP peers.
            socket.connect(pinned,Math.min(2500,remainingMillis(deadline)));
            socket.setSoTimeout(remainingMillis(deadline));
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            byte[] reserved = new byte[8];
            reserved[5] = 0x10;
            write(out,wire.encodeHandshake(new PeerWireCodec.Handshake(reserved,infoHash,peerId)).array(),deadline);
            long handshakeDeadline=Math.min(deadline,
                    System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(4));
            byte[] response = readExact(in,socket,PeerWireCodec.HANDSHAKE_LENGTH,
                    handshakeDeadline);
            PeerWireCodec.Handshake remote;
            try { remote = wire.decodeHandshake(ByteBuffer.wrap(response)).orElseThrow(); }
            catch (IllegalArgumentException e) { throw new IOException("Invalid BEP3 handshake",e); }
            if (!Arrays.equals(infoHash,remote.infoHash()) || Arrays.equals(peerId,remote.peerId()))
                throw new IOException("Peer identity mismatch");
            if ((remote.reserved()[5] & 0x10) == 0)
                throw new IOException("Peer does not support extensions");

            send(out,0,metadata.extensionHandshake(),deadline);
            MetadataExtensionCodec.ExtensionInfo extension = null;
            long extensionDeadline=Math.min(deadline,
                    System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(4));
            for (int skipped=0; extension==null && skipped<16; skipped++) {
                var message=receive(in,socket,extensionDeadline);
                if (message.id() != 20 || message.payload().length == 0 || message.payload()[0] != 0) continue;
                extension=metadata.parseExtensionHandshake(
                        Arrays.copyOfRange(message.payload(),1,message.payload().length));
            }
            if (extension == null || extension.metadataSize() < 1)
                throw new IOException("Peer did not advertise metadata size");

            MetadataAssembler assembler = new MetadataAssembler(infoHash,extension.metadataSize());
            for (int piece=0;piece<assembler.pieceCount();piece++) {
                send(out,extension.utMetadataId(),metadata.request(piece),deadline);
                boolean complete = false;
                for (int messages=0;!complete && messages<16;messages++) {
                    var frame=receive(in,socket,deadline);
                    if (frame.id() != 20 || frame.payload().length < 2
                            || Byte.toUnsignedInt(frame.payload()[0]) != 1) continue;
                    var received=metadata.parseMessage(Arrays.copyOfRange(frame.payload(),1,frame.payload().length));
                    if (received.piece() != piece) continue;
                    if (received.type() == 2) throw new IOException("Metadata piece rejected");
                    if (received.type() != 1) continue;
                    if (received.totalSize() != -1 && received.totalSize() != extension.metadataSize())
                        throw new IOException("Metadata size changed");
                    assembler.add(piece,received.data());
                    complete = true;
                }
                if (!complete) throw new IOException("Metadata piece response not received");
            }
            remainingMillis(deadline);
            return assembler.verifiedInfoDictionary();
        }
    }

    private void send(OutputStream out,int extensionId,byte[] payload,long deadline) throws IOException {
        byte[] body=new byte[payload.length+1];
        body[0]=(byte)extensionId;
        System.arraycopy(payload,0,body,1,payload.length);
        write(out,wire.encodeMessage(new PeerWireCodec.Message(20,body)).array(),deadline);
    }
    private void write(OutputStream out,byte[] bytes,long deadline) throws IOException {
        remainingMillis(deadline);
        out.write(bytes);
        out.flush();
        remainingMillis(deadline);
    }
    private PeerWireCodec.Message receive(InputStream input,Socket socket,long deadline) throws IOException {
        byte[] prefix=readExact(input,socket,4,deadline);
        int length=ByteBuffer.wrap(prefix).getInt();
        if (length < 0 || length > 256*1024) throw new IOException("Peer message too large");
        byte[] payload=readExact(input,socket,length,deadline);
        ByteBuffer frame=ByteBuffer.allocate(length+4).putInt(length).put(payload);
        frame.flip();
        try { return wire.decodeMessage(frame).orElseThrow(); }
        catch (IllegalArgumentException e) { throw new IOException("Invalid Peer message",e); }
    }
    private static byte[] readExact(InputStream input,Socket socket,int count,long deadline) throws IOException {
        byte[] data=new byte[count];
        int offset=0;
        while (offset < count) {
            socket.setSoTimeout(remainingMillis(deadline));
            int read=input.read(data,offset,count-offset);
            if (read == -1) throw new IOException("Peer disconnected during frame");
            if (read > 0) offset += read;
        }
        remainingMillis(deadline);
        return data;
    }
    private static int remainingMillis(long deadline) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("Metadata request interrupted");
        long nanos = deadline-System.nanoTime();
        if (nanos <= 0) throw new SocketTimeoutException("Metadata transfer deadline exceeded");
        long millis=Math.max(1, (nanos+999_999L)/1_000_000L);
        return (int)Math.min(Integer.MAX_VALUE,millis);
    }
}
