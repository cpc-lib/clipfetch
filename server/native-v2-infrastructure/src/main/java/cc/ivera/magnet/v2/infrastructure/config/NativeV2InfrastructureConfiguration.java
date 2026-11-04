package cc.ivera.magnet.v2.infrastructure.config;

import cc.ivera.magnet.v2.application.port.NativeV2Quota;
import cc.ivera.magnet.v2.application.port.NativeV2Storage;
import cc.ivera.magnet.v2.infrastructure.quota.RedisNativeV2Quota;
import cc.ivera.magnet.v2.infrastructure.storage.FileNativeV2Storage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.file.Path;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.magnet.native-v2.enabled", havingValue = "true")
public class NativeV2InfrastructureConfiguration {

    @Bean
    NativeV2Storage nativeV2Storage(NativeV2Properties properties, NativeV2PropertiesValidator validator) {
        return new FileNativeV2Storage(Path.of(properties.getStorageRoot()));
    }

    @Bean
    NativeV2Quota nativeV2Quota(
            StringRedisTemplate redis, NativeV2Properties properties, NativeV2PropertiesValidator validator) {
        return new RedisNativeV2Quota(redis, properties);
    }
}
