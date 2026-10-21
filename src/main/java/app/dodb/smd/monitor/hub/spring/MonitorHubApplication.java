package app.dodb.smd.monitor.hub.spring;

import app.dodb.smd.monitor.hub.HubConfig;
import app.dodb.smd.monitor.hub.MonitorHub;
import app.dodb.smd.monitor.hub.otlp.MonitorHubOtlpConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;

@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration
@EnableWebSocket
@EnableConfigurationProperties(MonitorHubProperties.class)
@Import(MonitorHubOtlpConfiguration.class)
public class MonitorHubApplication {

    public static void main(String[] args) {
        SpringApplication.run(MonitorHubApplication.class, args);
    }

    // The writer thread starts with the context and stops with it.
    @Bean(initMethod = "start", destroyMethod = "close")
    MonitorHub monitorHub(MonitorHubProperties properties) {
        var config = HubConfig.withDefaults()
            .memory(properties.memory().toBytes())
            .window(properties.window())
            .history(properties.history())
            .settle(properties.settle())
            .linkHorizon(properties.linkHorizon())
            .maxApplications(properties.maxApplications())
            .maxInstances(properties.maxInstances())
            .maxTypes(properties.maxTypes())
            .maxGroups(properties.maxGroups())
            .maxEdges(properties.maxEdges())
            .silentAfter(properties.silentAfter())
            .retireAfter(properties.retireAfter())
            .build();
        return new MonitorHub(config);
    }

    @Bean
    MonitorFrames monitorHubFrames() {
        return new MonitorFrames();
    }

    @Bean
    MonitorHubQueries monitorHubQueries(MonitorHub hub) {
        return new MonitorHubQueries(hub);
    }

    @Bean
    MonitorHubPage monitorHubPage() {
        return new MonitorHubPage();
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    MonitorFrameSocket monitorHubSocket(MonitorHub hub, MonitorFrames frames) {
        return new MonitorFrameSocket(hub, frames);
    }

    // The page's own origin is always allowed; others, such as the page's behind a proxy that forwards the upgrade as wss, only when listed.
    @Bean
    WebSocketConfigurer monitorHubWebSocketConfigurer(MonitorFrameSocket socket, MonitorHubProperties properties) {
        return registry -> registry.addHandler(socket, "/live").setAllowedOrigins(properties.allowedOrigins().toArray(String[]::new));
    }
}
