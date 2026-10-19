package cc.ivera.magnet.engine.piece;

import cc.ivera.magnet.domain.TorrentNativeWorkerInstanceMapper;
import cc.ivera.magnet.engine.config.NativeTorrentProperties;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NativeWorkerInstanceHeartbeatTest {
    @Test void disabledByDefaultDoesNotTouchDatabase() {
        var mapper=mock(TorrentNativeWorkerInstanceMapper.class);
        var heartbeat=new NativeWorkerInstanceHeartbeat(mapper,new NativeTorrentProperties());
        heartbeat.register();
        heartbeat.heartbeat();
        heartbeat.shutdown();
        assertFalse(heartbeat.isHealthy());
        verifyNoInteractions(mapper);
    }

    @Test void registrationRenewalAndShutdownAreTokenScoped() {
        var mapper=mock(TorrentNativeWorkerInstanceMapper.class);
        var settings=new NativeTorrentProperties();
        settings.setInstanceHeartbeatEnabled(true);
        when(mapper.databaseNow()).thenReturn(java.time.LocalDateTime.of(2026,10,15,15,30));
        when(mapper.registerAtomic(anyString(),anyString(),anyBoolean())).thenReturn(1);
        when(mapper.renewAtomic(anyString(),anyString(),anyBoolean())).thenReturn(1);
        when(mapper.isLiveAtDatabaseTime(anyString(),anyBoolean())).thenReturn(1);
        var heartbeat=new NativeWorkerInstanceHeartbeat(mapper,settings);
        heartbeat.register();
        heartbeat.register();
        assertTrue(heartbeat.isHealthy());
        heartbeat.heartbeat();
        heartbeat.shutdown();
        assertFalse(heartbeat.isHealthy());
        verify(mapper,times(1)).registerAtomic(anyString(),anyString(),anyBoolean());
        verify(mapper,times(1)).renewAtomic(anyString(),anyString(),anyBoolean());
        verify(mapper,times(1)).stop(anyString(),anyString());
    }

    @Test void databaseLeaseExpiryFencesWorkerEvenWhenLocalHeartbeatFlagIsHealthy() {
        var mapper=mock(TorrentNativeWorkerInstanceMapper.class);
        var settings=new NativeTorrentProperties();
        settings.setInstanceHeartbeatEnabled(true);
        when(mapper.databaseNow()).thenReturn(java.time.LocalDateTime.of(2026,10,15,15,30));
        when(mapper.registerAtomic(anyString(),anyString(),anyBoolean())).thenReturn(1);
        when(mapper.isLiveAtDatabaseTime(anyString(),anyBoolean())).thenReturn(0);
        var heartbeat=new NativeWorkerInstanceHeartbeat(mapper,settings);
        var lost=new java.util.concurrent.atomic.AtomicInteger();
        heartbeat.setOnLeaseLost(lost::incrementAndGet);
        heartbeat.register();
        assertFalse(heartbeat.isHealthy());
        assertEquals(1,lost.get());
        heartbeat.heartbeat();
        assertFalse(heartbeat.isHealthy());
        verify(mapper,never()).renewAtomic(anyString(),anyString(),anyBoolean());
    }

    @Test void databaseOutageAlsoFencesWorkerFailClosed() {
        var mapper=mock(TorrentNativeWorkerInstanceMapper.class);
        var settings=new NativeTorrentProperties();
        settings.setInstanceHeartbeatEnabled(true);
        when(mapper.databaseNow()).thenReturn(java.time.LocalDateTime.of(2026,10,15,15,30));
        when(mapper.registerAtomic(anyString(),anyString(),anyBoolean())).thenReturn(1);
        when(mapper.isLiveAtDatabaseTime(anyString(),anyBoolean()))
                .thenThrow(new IllegalStateException("MySQL unavailable"));
        var heartbeat=new NativeWorkerInstanceHeartbeat(mapper,settings);
        var lost=new java.util.concurrent.atomic.AtomicInteger();
        heartbeat.setOnLeaseLost(lost::incrementAndGet);
        heartbeat.register();
        assertFalse(heartbeat.isHealthy());
        assertFalse(heartbeat.isHealthy());
        assertEquals(1,lost.get());
    }

    @Test void lostLeaseTriggersContainmentOnlyOnce() {
        var mapper=mock(TorrentNativeWorkerInstanceMapper.class);
        var settings=new NativeTorrentProperties();
        settings.setInstanceHeartbeatEnabled(true);
        when(mapper.databaseNow()).thenReturn(java.time.LocalDateTime.of(2026,10,15,15,30));
        when(mapper.registerAtomic(anyString(),anyString(),anyBoolean())).thenReturn(1);
        var heartbeat=new NativeWorkerInstanceHeartbeat(mapper,settings);
        var stops=new java.util.concurrent.atomic.AtomicInteger();
        heartbeat.setOnLeaseLost(stops::incrementAndGet);
        heartbeat.register();
        heartbeat.heartbeat();
        heartbeat.heartbeat();
        assertEquals(1,stops.get());
        assertFalse(heartbeat.isHealthy());
    }

    @Test void lostLeaseNeverRenewsAgain() {
        var mapper=mock(TorrentNativeWorkerInstanceMapper.class);
        var settings=new NativeTorrentProperties();
        settings.setInstanceHeartbeatEnabled(true);
        when(mapper.databaseNow()).thenReturn(java.time.LocalDateTime.of(2026,10,15,15,30));
        when(mapper.registerAtomic(anyString(),anyString(),anyBoolean())).thenReturn(1);
        var heartbeat=new NativeWorkerInstanceHeartbeat(mapper,settings);
        heartbeat.register();
        heartbeat.heartbeat();
        assertFalse(heartbeat.isHealthy());
        heartbeat.heartbeat();
        verify(mapper,times(1)).renewAtomic(anyString(),anyString(),anyBoolean());
    }
}
