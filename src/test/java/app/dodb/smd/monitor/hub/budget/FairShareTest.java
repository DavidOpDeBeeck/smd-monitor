package app.dodb.smd.monitor.hub.budget;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FairShareTest {

    @Test
    void of_allowance_sharesItEquallyAmongTheApplications() {
        assertThat(FairShare.of(90, 3)).isEqualTo(30);
        assertThat(FairShare.of(90, 0)).isEqualTo(90);
    }
}
