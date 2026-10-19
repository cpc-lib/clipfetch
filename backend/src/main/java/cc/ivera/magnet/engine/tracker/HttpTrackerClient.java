package cc.ivera.magnet.engine.tracker;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.SSLParameters;
import cc.ivera.magnet.engine.piece.NativeNetworkBudget;

/**
 * BEP3 HTTP(S) announce client. Rejects redirects, pins a vetted DNS address,
 * limits response size and uses an absolute deadline. TLS verifies the original
 * hostname (SNI and HTTPS endpoint identification) over the pinned TCP socket.
 */
public final class HttpTrackerClient {
    private static final int MAX_HEADERS = 16 * 1024;
    private static final int MAX_BODY = 2 * 1024 * 1024;
    private final TrackerAddressPolicy policy;

    public HttpTrackerClient(TrackerAddressPolicy policy) { this.policy = policy; }

    public TrackerProtocol.AnnounceResponse announce(URI tracker, byte[] infoHash, byte[] peerId,
            long downloaded, long left, long uploaded, int listenPort, Duration timeout) throws IOException {
        if (tracker == null || !("http".equalsIgnoreCase(tracker.getScheme())
                || "https".equalsIgnoreCase(tracker.getScheme())))
            throw new IllegalArgumentException("Only HTTP and HTTPS Trackers supported by this transport");
        if (infoHash == null || infoHash.length != 20 || peerId == null || peerId.length != 20
                || downloaded < 0 || left < 0 || uploaded < 0 || listenPort < 1 || listenPort > 65535
                || timeout == null || timeout.isZero() || timeout.isNegative()
                || timeout.compareTo(Duration.ofSeconds(30)) > 0)
            throw new IllegalArgumentException("Invalid Tracker announce arguments");

        timeout=NativeNetworkBudget.cap(timeout);
        long deadline=System.nanoTime()+timeout.toNanos();
        InetSocketAddress target=policy.target(tracker); // Resolve once, validate and pin IP.
        String base=tracker.getRawPath();
        if (base == null || base.isEmpty()) base="/";
        String query="info_hash="+TrackerProtocol.percentEncode(infoHash)
                +"&peer_id="+TrackerProtocol.percentEncode(peerId)
                +"&port="+listenPort+"&uploaded="+uploaded
                +"&downloaded="+downloaded+"&left="+left+"&compact=1";
        String existing=tracker.getRawQuery();
        String path=base+(existing == null ? "?" : "?"+existing+"&")+query;
        if (path.indexOf('\r')>=0 || path.indexOf('\n')>=0)
            throw new IllegalArgumentException("Invalid Tracker URI path");

        String host=tracker.getHost();
        if (host.contains(":") && !host.startsWith("[")) host="["+host+"]";
        String authority=host+(tracker.getPort()==-1 ? "" : ":"+tracker.getPort());
        try (Socket pinned=new Socket()) {
            pinned.connect(target,remainingMillis(deadline));
            pinned.setSoTimeout(remainingMillis(deadline));
            Socket transport=pinned;
            if("https".equalsIgnoreCase(tracker.getScheme())) {
                // The underlying TCP connection never performs a second DNS lookup.
                SSLSocket tls=(SSLSocket)((SSLSocketFactory)SSLSocketFactory.getDefault())
                        .createSocket(pinned,tracker.getHost(),target.getPort(),false);
                SSLParameters params=tls.getSSLParameters();
                params.setEndpointIdentificationAlgorithm("HTTPS");
                tls.setSSLParameters(params);
                tls.setSoTimeout(remainingMillis(deadline));
                tls.startHandshake();
                transport=tls;
            }
            try {
                transport.setSoTimeout(remainingMillis(deadline));
                OutputStream out=transport.getOutputStream();
                String request="GET "+path+" HTTP/1.1\r\nHost: "+authority
                        +"\r\nAccept: */*\r\nConnection: close\r\nUser-Agent: ClipFetch-NativeBT/0.1\r\n\r\n";
                out.write(request.getBytes(StandardCharsets.ISO_8859_1));
                out.flush();
                remainingMillis(deadline);
                return TrackerProtocol.parseHttpResponse(readResponse(transport,deadline));
            } finally {
                if(transport!=pinned) {
                    // JSSE may wait indefinitely for an uncooperative Tracker's
                    // close_notify when closing an SSLSocket layered over an
                    // otherwise still-open TCP socket. We have already consumed
                    // the one-shot announce: abort underlying TCP first so TLS
                    // shutdown cannot block the entire native download round.
                    // The outer try-with-resources still owns the pinned socket.
                    pinned.close();
                    try {transport.close();}
                    catch(IOException ignored) {
                        // The parsed response/earlier failure, not TLS shutdown,
                        // determines the result of this one-shot announce.
                    }
                }
            }
        }
    }

    private static byte[] readResponse(Socket socket,long deadline) throws IOException {
        InputStream input=socket.getInputStream();
        ByteArrayOutputStream headers=new ByteArrayOutputStream();
        int match=0;
        byte[] ending={'\r','\n','\r','\n'};
        while (match < 4) {
            int next=readByte(input,socket,deadline);
            if (next < 0) throw new IOException("Tracker disconnected before headers");
            headers.write(next);
            if (headers.size()>MAX_HEADERS) throw new IOException("Tracker headers too large");
            match=next==ending[match] ? match+1 : (next=='\r' ? 1 : 0);
        }
        String head=headers.toString(StandardCharsets.ISO_8859_1);
        String[] lines=head.split("\r\n");
        if (!lines[0].matches("HTTP/1\\.[01] 200(?: .*)?"))
            throw new IOException("Tracker non-200 HTTP status; redirects are not followed");

        String length=null;
        for (int i=1;i<lines.length;i++) {
            int colon=lines[i].indexOf(':');
            if (colon<0) throw new IOException("Invalid HTTP header");
            String key=lines[i].substring(0,colon).trim().toLowerCase(Locale.ROOT);
            String value=lines[i].substring(colon+1).trim();
            if (key.equals("transfer-encoding"))
                throw new IOException("Chunked Tracker response not yet supported");
            if (key.equals("content-length")) {
                if (length != null) throw new IOException("Duplicate content-length");
                length=value;
            }
        }

        ByteArrayOutputStream body=new ByteArrayOutputStream();
        byte[] chunk=new byte[8192];
        if (length != null) {
            long expected;
            try { expected=Long.parseLong(length); }
            catch (NumberFormatException e) { throw new IOException("Invalid content-length",e); }
            if (expected<0 || expected>MAX_BODY) throw new IOException("Tracker body too large");
            while (body.size()<expected) {
                int toRead=(int)Math.min(chunk.length,expected-body.size());
                int n=readChunk(input,socket,chunk,toRead,deadline);
                if (n<0) throw new IOException("Incomplete Tracker body");
                if (n>0) body.write(chunk,0,n);
            }
        } else {
            int n;
            while ((n=readChunk(input,socket,chunk,chunk.length,deadline))>=0) {
                if (body.size()+(long)n>MAX_BODY) throw new IOException("Tracker body too large");
                if (n>0) body.write(chunk,0,n);
            }
        }
        remainingMillis(deadline);
        return body.toByteArray();
    }
    private static int readByte(InputStream input,Socket socket,long deadline) throws IOException {
        socket.setSoTimeout(remainingMillis(deadline));
        int b=input.read();
        remainingMillis(deadline);
        return b;
    }
    private static int readChunk(InputStream input,Socket socket,byte[] data,int size,long deadline) throws IOException {
        socket.setSoTimeout(remainingMillis(deadline));
        int count=input.read(data,0,size);
        remainingMillis(deadline);
        return count;
    }
    private static int remainingMillis(long deadline) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("Tracker request interrupted");
        long nanos=deadline-System.nanoTime();
        if (nanos<=0) throw new SocketTimeoutException("Tracker response deadline exceeded");
        return (int)Math.min(Integer.MAX_VALUE,Math.max(1,(nanos+999_999L)/1_000_000L));
    }
}
