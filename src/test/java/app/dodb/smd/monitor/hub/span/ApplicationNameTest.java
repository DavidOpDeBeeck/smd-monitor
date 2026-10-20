package app.dodb.smd.monitor.hub.span;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApplicationNameTest {

    @Test
    void create_nameOpenTelemetryAndSpringAllow_keepsIt() {
        var name = new ApplicationName("unknown_service:java | Order Service@eu/ébène");

        assertThat(name.value()).isEqualTo("unknown_service:java | Order Service@eu/ébène");
    }

    @Test
    void create_nameOf255Characters_keepsIt() {
        var name = new ApplicationName("a".repeat(255));

        assertThat(name.value()).hasSize(255);
    }

    @Test
    void create_emptyName_throwsNamingTheExpectedLength() {
        assertThatThrownBy(() -> new ApplicationName(""))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("ApplicationName requires 1 to 255 characters; got 0 characters");
    }

    @Test
    void create_nameLongerThan255Characters_throwsNamingItsLength() {
        assertThatThrownBy(() -> new ApplicationName("a".repeat(256)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("ApplicationName requires 1 to 255 characters; got 256 characters");
    }
}
