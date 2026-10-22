package app.dodb.smd.monitor.hub.web;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OriginsTest {

    private static final List<String> NONE = List.of();

    @Test
    void allowed_noOrigin_isAllowed() {
        assertThat(Origins.allowed(null, "127.0.0.1:8080", NONE)).isTrue();
    }

    @Test
    void allowed_thePagesOwnOrigin_isAllowed() {
        assertThat(Origins.allowed("http://127.0.0.1:8080", "127.0.0.1:8080", NONE)).isTrue();
    }

    @Test
    void allowed_ownOriginOnTheDefaultPort_isAllowed() {
        assertThat(Origins.allowed("https://Monitor.example", "monitor.example", NONE)).isTrue();
    }

    @Test
    void allowed_anotherPortOfTheSameHost_isRefused() {
        assertThat(Origins.allowed("http://127.0.0.1:9000", "127.0.0.1:8080", NONE)).isFalse();
    }

    @Test
    void allowed_foreignOrigin_isRefused() {
        assertThat(Origins.allowed("https://untrusted.example", "127.0.0.1:8080", NONE)).isFalse();
    }

    @Test
    void allowed_listedOrigin_isAllowed() {
        assertThat(Origins.allowed("https://monitor.example", "hub:8080", List.of("https://monitor.example"))).isTrue();
    }

    @Test
    void allowed_opaqueOrigin_isRefused() {
        assertThat(Origins.allowed("null", "127.0.0.1:8080", NONE)).isFalse();
    }

    @Test
    void allowed_unreadableOrigin_isRefused() {
        assertThat(Origins.allowed("http://exa mple", "127.0.0.1:8080", NONE)).isFalse();
    }
}
