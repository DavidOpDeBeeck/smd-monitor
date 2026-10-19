package app.dodb.smd.monitor.hub.spring;

import app.dodb.smd.monitor.hub.HubConfig;
import app.dodb.smd.monitor.hub.MonitorHub;
import app.dodb.smd.monitor.hub.otlp.MonitorHubOtlpConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
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

    // Defaults only: the hub has no authentication, so it listens on the loopback address unless server.address says otherwise.
    public static void main(String[] args) {
        new SpringApplicationBuilder(MonitorHubApplication.class)
            .properties("server.address=127.0.0.1", "spring.application.name=smd-monitor-hub")
            .run(args);
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

    @Bean
    WebSocketConfigurer monitorHubWebSocketConfigurer(MonitorFrameSocket socket) {
        return registry -> registry.addHandler(socket, "/live");
    }
}
