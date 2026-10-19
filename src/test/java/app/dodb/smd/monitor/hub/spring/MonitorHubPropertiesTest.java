package app.dodb.smd.monitor.hub.spring;

import app.dodb.smd.monitor.hub.HubConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MonitorHubPropertiesTest {

    @Test
    void bind_nothingSet_matchesTheHubDefaults() {
        var properties = bind(Map.of());

        var defaults = HubConfig.withDefaults().build();
        assertThat(properties.memory().toBytes()).isEqualTo(defaults.memory());
        assertThat(properties.linkHorizon()).isEqualTo(defaults.linkHorizon());
        assertThat(properties.window()).isEqualTo(defaults.window());
        assertThat(properties.history()).isEqualTo(defaults.history());
        assertThat(properties.settle()).isEqualTo(defaults.settle());
        assertThat(properties.maxApplications()).isEqualTo(defaults.maxApplications());
        assertThat(properties.maxInstances()).isEqualTo(defaults.maxInstances());
        assertThat(properties.maxTypes()).isEqualTo(defaults.maxTypes());
        assertThat(properties.maxGroups()).isEqualTo(defaults.maxGroups());
        assertThat(properties.maxEdges()).isEqualTo(defaults.maxEdges());
        assertThat(properties.silentAfter()).isEqualTo(defaults.silentAfter());
        assertThat(properties.retireAfter()).isEqualTo(defaults.retireAfter());
    }

    @Test
    void bind_limitsSet_bindsEachLimit() {
        var properties = bind(keysAndValues("smd.monitor.hub.memory", "1GB", "smd.monitor.hub.window", "30s", "smd.monitor.hub.history", "15m", "smd.monitor.hub.settle", "2s", "smd.monitor.hub.link-horizon", "2m",
            "smd.monitor.hub.max-applications", "10", "smd.monitor.hub.max-instances", "20", "smd.monitor.hub.max-types", "300", "smd.monitor.hub.max-groups", "40",
            "smd.monitor.hub.max-edges", "900", "smd.monitor.hub.silent-after", "30s", "smd.monitor.hub.retire-after", "1h"));

        assertThat(properties).isEqualTo(new MonitorHubProperties(DataSize.ofGigabytes(1), Duration.ofSeconds(30), Duration.ofMinutes(15), Duration.ofSeconds(2), Duration.ofMinutes(2), 10, 20, 300, 40, 900,
            Duration.ofSeconds(30), Duration.ofHours(1)));
    }

    private static Map<String, String> keysAndValues(String... keysAndValues) {
        var properties = new HashMap<String, String>();
        for (var index = 0; index < keysAndValues.length; index += 2) {
            properties.put(keysAndValues[index], keysAndValues[index + 1]);
        }
        return properties;
    }

    private static MonitorHubProperties bind(Map<String, String> properties) {
        return new Binder(new MapConfigurationPropertySource(properties)).bindOrCreate("smd.monitor.hub", MonitorHubProperties.class);
    }
}
