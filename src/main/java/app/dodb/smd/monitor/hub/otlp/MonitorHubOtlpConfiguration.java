package app.dodb.smd.monitor.hub.otlp;

import app.dodb.smd.monitor.hub.MonitorHub;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class MonitorHubOtlpConfiguration {

    @Bean
    OtlpTraceController monitorHubOtlpTraceController(MonitorHub hub) {
        return new OtlpTraceController(hub);
    }
}
