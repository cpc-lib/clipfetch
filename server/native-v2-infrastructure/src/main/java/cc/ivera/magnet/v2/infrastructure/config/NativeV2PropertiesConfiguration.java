package cc.ivera.magnet.v2.infrastructure.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NativeV2Properties.class)
public class NativeV2PropertiesConfiguration {

    @Bean
    @ConditionalOnProperty(name = "app.magnet.native-v2.enabled", havingValue = "true")
    NativeV2PropertiesValidator nativeV2PropertiesValidator(
            NativeV2Properties properties,
            @Value("${spring.datasource.url:}") String datasourceUrl) {
        return new NativeV2PropertiesValidator(properties, datasourceUrl);
    }
}

final class NativeV2PropertiesValidator implements InitializingBean {

    private final NativeV2Properties properties;
    private final String datasourceUrl;

    NativeV2PropertiesValidator(NativeV2Properties properties, String datasourceUrl) {
        this.properties = properties;
        this.datasourceUrl = datasourceUrl;
    }

    @Override
    public void afterPropertiesSet() {
        properties.validate(datasourceUrl);
    }
}
