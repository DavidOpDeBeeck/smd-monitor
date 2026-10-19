package app.dodb.smd.monitor.hub;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HubConfigTest {

    @Test
    void build_withDefaults_usesTheDocumentedDefaults() {
        var config = HubConfig.withDefaults().build();

        assertThat(config).isEqualTo(new HubConfig(256L * 1024 * 1024, Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofSeconds(5), Duration.ofSeconds(60), 100, 256, 1_000,
            200, 5_000, Duration.ofSeconds(10), Duration.ofMinutes(10)));
    }

    @Test
    void build_historyOfTheWindow_isAccepted() {
        var config = HubConfig.withDefaults().history(Duration.ofSeconds(60)).build();

        assertThat(config.history()).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void build_windowAndHistoryOfFifteenMinutes_isAccepted() {
        var config = HubConfig.withDefaults().window(Duration.ofMinutes(15)).history(Duration.ofMinutes(15)).build();

        assertThat(config).extracting(HubConfig::window, HubConfig::history).containsExactly(Duration.ofMinutes(15), Duration.ofMinutes(15));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PT9S", "PT15M1S", "PT10.5S"})
    void build_windowOutOfRange_rejectsWithContext(String window) {
        assertThatThrownBy(() -> HubConfig.withDefaults().window(Duration.parse(window)).history(Duration.ofMinutes(15)).build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("window", "whole seconds between 10 and 900 seconds", window);
    }

    @Test
    void build_historyShorterThanTheWindow_rejectsWithContext() {
        assertThatThrownBy(() -> HubConfig.withDefaults().window(Duration.ofMinutes(10)).history(Duration.ofMinutes(5)).build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("history", "between the window of 600 and 900 seconds", "PT5M");
    }

    @ParameterizedTest
    @ValueSource(strings = {"PT59S", "PT15M1S", "PT90.5S"})
    void build_historyOutOfRange_rejectsWithContext(String history) {
        assertThatThrownBy(() -> HubConfig.withDefaults().history(Duration.parse(history)).build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("history", "whole seconds between the window of 60 and 900 seconds", history);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PT0S", "PT11S", "PT2.5S"})
    void build_settleOutOfRange_rejectsWithContext(String settle) {
        assertThatThrownBy(() -> HubConfig.withDefaults().settle(Duration.parse(settle)).build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("settle", "whole seconds between 1 and 10 seconds", settle);
    }

    @ParameterizedTest
    @ValueSource(longs = {32L * 1024 * 1024 - 1, 8L * 1024 * 1024 * 1024 + 1})
    void build_memoryOutOfRange_rejectsWithContext(long memory) {
        assertThatThrownBy(() -> HubConfig.withDefaults().memory(memory).build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("memory", "(32MB)", "(8GB)", String.valueOf(memory));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PT9S", "PT5M1S", "PT30.5S"})
    void build_linkHorizonOutOfRange_rejectsWithContext(String linkHorizon) {
        assertThatThrownBy(() -> HubConfig.withDefaults().linkHorizon(Duration.parse(linkHorizon)).build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("link horizon", "whole seconds between 10 and the history of 300 seconds", linkHorizon);
    }

    @Test
    void build_noApplications_rejectsWithContext() {
        assertThatThrownBy(() -> HubConfig.withDefaults().maxApplications(0).build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("max applications", "at least 1", "got 0");
    }

    @Test
    void build_noInstances_rejectsWithContext() {
        assertThatThrownBy(() -> HubConfig.withDefaults().maxInstances(0).build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("max instances", "at least 1", "got 0");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 8_001})
    void build_maxTypesOutOfRange_rejectsWithContext(int maxTypes) {
        assertThatThrownBy(() -> HubConfig.withDefaults().maxTypes(maxTypes).build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("max types", "between 1 and 8000", "got " + maxTypes);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2_001})
    void build_maxGroupsOutOfRange_rejectsWithContext(int maxGroups) {
        assertThatThrownBy(() -> HubConfig.withDefaults().maxGroups(maxGroups).build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("max groups", "between 1 and 2000", "got " + maxGroups);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 80_001})
    void build_maxEdgesOutOfRange_rejectsWithContext(int maxEdges) {
        assertThatThrownBy(() -> HubConfig.withDefaults().maxEdges(maxEdges).build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("max edges", "between 1 and 80000", "got " + maxEdges);
    }

    @Test
    void build_silentAfterNotPositive_rejectsWithContext() {
        assertThatThrownBy(() -> HubConfig.withDefaults().silentAfter(Duration.ZERO).build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("silent-after", "positive", "PT0S");
    }

    @Test
    void build_retireAfterNotLongerThanSilentAfter_rejectsWithContext() {
        assertThatThrownBy(() -> HubConfig.withDefaults().silentAfter(Duration.ofMinutes(1)).retireAfter(Duration.ofMinutes(1)).build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("retire-after", "longer than silent-after PT1M", "got PT1M");
    }
}
