package cc.ivera.magnet.engine.tracker;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class HttpTrackerDeadlineTest {
    @Test void trickleResponseCannotExtendWholeHttpTimeout() throws Exception {
        try (ServerSocket server=new ServerSocket(0)) {
            var pool=Executors.newSingleThreadExecutor();
            try {
                var fixture=pool.submit(() -> {
                    try (var socket=server.accept()) {
                        socket.setSoTimeout(3000);
                        var in=socket.getInputStream();
                        StringBuilder header=new StringBuilder();
                        int next;
                        while ((next=in.read())!=-1) {
                            header.append((char)next);
                            if (header.toString().endsWith("\r\n\r\n")) break;
                        }
                        var out=socket.getOutputStream();
                        out.write(("HTTP/1.1 200 OK\r\nContent-Length: 80\r\n\r\n")
                                .getBytes(StandardCharsets.US_ASCII));
                        out.flush();
                        for (int i=0;i<25;i++) {
                            out.write('x');
                            out.flush();
                            Thread.sleep(70);
                        }
                    } catch (Exception ignored) {
                        // Client must close when its absolute budget expires.
                    }
                });
                long start=System.nanoTime();
                assertThrows(IOException.class,() -> new HttpTrackerClient(new TrackerAddressPolicy(true))
                        .announce(URI.create("http://127.0.0.1:"+server.getLocalPort()+"/announce"),
                                new byte[20],new byte[20],0,1,0,6881,Duration.ofMillis(450)));
                long elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);
                assertTrue(elapsed<1300,"Unexpectedly long tracker call: "+elapsed+"ms");
                fixture.get(5,TimeUnit.SECONDS);
            } finally { pool.shutdownNow(); }
        }
    }
}
