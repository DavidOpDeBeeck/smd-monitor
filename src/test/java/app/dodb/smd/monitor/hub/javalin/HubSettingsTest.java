package app.dodb.smd.monitor.hub.javalin;

import app.dodb.smd.monitor.hub.HubConfig;
import io.avaje.config.Configuration;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HubSettingsTest {

    @Test
    void from_onlyTheBundledApplicationYml_isTheDefaults() {
        var settings = settings(Map.of());

        assertThat(settings).isEqualTo(new HubSettings(8080, null, 4318, null, List.of(), 128, HubConfig.withDefaults().build()));
    }

    @Test
    void from_everythingSet_readsEachProperty() {
        var settings = settings(Map.ofEntries(Map.entry("smd.monitor.hub.ui.port", "9090"), Map.entry("smd.monitor.hub.ui.address", "127.0.0.1"),
            Map.entry("smd.monitor.hub.otlp.port", "14318"), Map.entry("smd.monitor.hub.otlp.address", "10.0.0.1"),
            Map.entry("smd.monitor.hub.ui.allowed-origins", "https://monitor.example, https://ops.example"), Map.entry("smd.monitor.hub.ui.max-viewers", "32"),
            Map.entry("smd.monitor.hub.memory", "1073741824"),
            Map.entry("smd.monitor.hub.window", "PT30S"), Map.entry("smd.monitor.hub.history", "PT15M"), Map.entry("smd.monitor.hub.settle", "PT2S"),
            Map.entry("smd.monitor.hub.link-horizon", "PT2M"), Map.entry("smd.monitor.hub.max-applications", "10"), Map.entry("smd.monitor.hub.max-instances", "20"),
            Map.entry("smd.monitor.hub.max-types", "300"), Map.entry("smd.monitor.hub.max-groups", "40"), Map.entry("smd.monitor.hub.max-edges", "900"),
            Map.entry("smd.monitor.hub.silent-after", "PT30S"), Map.entry("smd.monitor.hub.retire-after", "PT1H")));

        assertThat(settings).isEqualTo(new HubSettings(9090, "127.0.0.1", 14318, "10.0.0.1", List.of("https://monitor.example", "https://ops.example"), 32,
            HubConfig.withDefaults().memory(1L << 30).window(Duration.ofSeconds(30)).history(Duration.ofMinutes(15)).settle(Duration.ofSeconds(2))
                .linkHorizon(Duration.ofMinutes(2)).maxApplications(10).maxInstances(20).maxTypes(300).maxGroups(40).maxEdges(900)
                .silentAfter(Duration.ofSeconds(30)).retireAfter(Duration.ofHours(1)).build()));
    }

    @Test
    void from_blankAllowedOrigins_skipsThem() {
        var settings = settings(Map.of("smd.monitor.hub.ui.allowed-origins", " , https://monitor.example,"));

        assertThat(settings.allowedOrigins()).containsExactly("https://monitor.example");
    }

    @Test
    void from_samePortForThePageAndTheExports_failsNamingBothProperties() {
        assertThatThrownBy(() -> settings(Map.of("smd.monitor.hub.ui.port", "18080", "smd.monitor.hub.otlp.port", "18080")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("smd.monitor.hub.otlp.port must differ from smd.monitor.hub.ui.port; both are 18080");
    }

    @Test
    void from_noViewersAllowed_failsNamingTheProperty() {
        assertThatThrownBy(() -> settings(Map.of("smd.monitor.hub.ui.max-viewers", "0")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("smd.monitor.hub.ui.max-viewers must be at least 1, as no page could connect otherwise; got 0");
    }

    @Test
    void from_freePortForBoth_isAllowed() {
        var settings = settings(Map.of("smd.monitor.hub.ui.port", "0", "smd.monitor.hub.otlp.port", "0"));

        assertThat(settings.uiPort()).isZero();
        assertThat(settings.otlpPort()).isZero();
    }

    private static HubSettings settings(Map<String, String> properties) {
        return HubSettings.from(Configuration.builder().load("application.yml").putAll(properties).build());
    }
}
