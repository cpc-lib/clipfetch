package cc.ivera.magnet.engine.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Enable periodic heartbeats only by explicit native instance opt-in. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix="app.magnet.native-engine",
        name="instance-heartbeat-enabled",havingValue="true")
public class NativeHeartbeatSchedulingConfiguration {
}
