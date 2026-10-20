package app.dodb.smd.monitor.hub.otlp;

import app.dodb.smd.monitor.hub.MonitorHub;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OtlpProperties.class)
public class MonitorHubOtlpConfiguration {

    @Bean
    OtlpConnector monitorHubOtlpConnector(OtlpProperties properties) {
        return new OtlpConnector(properties);
    }

    @Bean
    OtlpPortFilter monitorHubOtlpPortFilter(OtlpConnector connector) {
        return new OtlpPortFilter(connector);
    }

    @Bean
    OtlpTraceController monitorHubOtlpTraceController(MonitorHub hub) {
        return new OtlpTraceController(hub);
    }
}
