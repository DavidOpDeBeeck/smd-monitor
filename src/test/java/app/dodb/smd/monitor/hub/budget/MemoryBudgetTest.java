package app.dodb.smd.monitor.hub.budget;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MemoryBudgetTest {

    private static final long MEGABYTE = 1024 * 1024;

    @Test
    void of_bytes_setsTheFixedCeilings() {
        var budget = MemoryBudget.of(1_000 * MEGABYTE);

        assertThat(budget.total()).isEqualTo(1_000 * MEGABYTE);
        assertThat(budget.queues()).isEqualTo(100 * MEGABYTE);
        assertThat(budget.counting()).isEqualTo(500 * MEGABYTE);
        assertThat(budget.join()).isEqualTo(250 * MEGABYTE);
        assertThat(budget.queuedSpans()).isEqualTo((int) (100 * MEGABYTE / Footprint.QUEUED_SPAN));
    }

    @Test
    void specimens_countingAndJoinTakingTheirCeilings_leavesTheSpecimensTheRest() {
        var budget = MemoryBudget.of(1_000 * MEGABYTE);

        assertThat(budget.specimens(0, 0)).isEqualTo(900 * MEGABYTE);
        assertThat(budget.specimens(500 * MEGABYTE, 250 * MEGABYTE)).isEqualTo(150 * MEGABYTE);
    }

    @Test
    void specimens_countingAndJoinBeyondTheBudget_leavesNothing() {
        var budget = MemoryBudget.of(1_000 * MEGABYTE);

        assertThat(budget.specimens(800 * MEGABYTE, 300 * MEGABYTE)).isZero();
    }

    @Test
    void new_productWithoutBytes_rejectsWithContext() {
        assertThatThrownBy(() -> new MemoryBudget(100, 10, 50, 0, 100))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("positive bytes", "0 for the join");
    }

    @Test
    void checkHeap_budgetWithinAQuarterOfTheHeap_fits() {
        assertThat(MemoryBudget.checkHeap(256 * MEGABYTE, 1_024 * MEGABYTE)).isInstanceOf(HeapCheck.Fits.class);
    }

    @Test
    void checkHeap_budgetAboveAQuarterOfTheHeap_warnsNamingBoth() {
        var check = MemoryBudget.checkHeap(257 * MEGABYTE, 1_024 * MEGABYTE);

        assertThat(check).isInstanceOfSatisfying(HeapCheck.Warns.class,
            warns -> assertThat(warns.message()).contains("smd.monitor.hub.memory", String.valueOf(257 * MEGABYTE), String.valueOf(1_024 * MEGABYTE), "25%"));
    }

    @Test
    void checkHeap_budgetAboveThreeQuartersOfTheHeap_exceedsNamingBoth() {
        var check = MemoryBudget.checkHeap(769 * MEGABYTE, 1_024 * MEGABYTE);

        assertThat(check).isInstanceOfSatisfying(HeapCheck.Exceeds.class,
            exceeds -> assertThat(exceeds.message()).contains("smd.monitor.hub.memory", String.valueOf(769 * MEGABYTE), String.valueOf(1_024 * MEGABYTE), "75%",
                "-Xmx"));
    }

    @Test
    void checkHeap_jvmWithoutAMaximumHeap_fits() {
        assertThat(MemoryBudget.checkHeap(8_192 * MEGABYTE, Long.MAX_VALUE)).isInstanceOf(HeapCheck.Fits.class);
    }
}
