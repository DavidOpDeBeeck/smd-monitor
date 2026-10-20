package app.dodb.smd.monitor.hub.span;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InstanceNameTest {

    @Test
    void create_nameOpenTelemetryAllows_keepsIt() {
        var name = new InstanceName("my host | pod@eu/1");

        assertThat(name.value()).isEqualTo("my host | pod@eu/1");
    }

    @Test
    void create_nameOf200Characters_keepsIt() {
        var name = new InstanceName("a".repeat(200));

        assertThat(name.value()).hasSize(200);
    }

    @Test
    void create_emptyName_throwsNamingTheExpectedLength() {
        assertThatThrownBy(() -> new InstanceName(""))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("InstanceName requires 1 to 200 characters; got 0 characters");
    }

    @Test
    void create_nameLongerThan200Characters_throwsNamingItsLength() {
        assertThatThrownBy(() -> new InstanceName("a".repeat(201)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("InstanceName requires 1 to 200 characters; got 201 characters");
    }
}
