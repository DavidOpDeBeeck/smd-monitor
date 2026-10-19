package app.dodb.smd.monitor.hub;

import app.dodb.smd.monitor.hub.RecordingInstance.MutableClock;
import app.dodb.smd.monitor.hub.budget.Budgets;
import app.dodb.smd.monitor.hub.budget.MemoryBudget;
import app.dodb.smd.monitor.hub.health.ApplicationHealth;
import app.dodb.smd.monitor.hub.health.HealthReason;
import app.dodb.smd.monitor.hub.health.HealthView;
import app.dodb.smd.monitor.hub.span.Outcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.function.Consumer;

import static app.dodb.smd.monitor.hub.RecordingInstance.BILLING;
import static app.dodb.smd.monitor.hub.RecordingInstance.OPEN;
import static app.dodb.smd.monitor.hub.RecordingInstance.TICKETS;
import static app.dodb.smd.monitor.hub.RecordingInstance.with;
import static org.assertj.core.api.Assertions.assertThat;

// The hub within its memory budget under generated load: four applications sending 800 spans per second over a 3-minute history and 20
// seconds more.
class MonitorHubBudgetTest {

    private static final LoadProfile PROFILE = new LoadProfile(4, 3, 40, 8, 800, Duration.ofMinutes(1), Duration.ofMinutes(3), HubConfig.DEFAULT_MEMORY);
    private static final long SEED = 7;
    private static final long MEGABYTE = 1024 * 1024;

    private final MutableClock clock = new MutableClock();

    @ParameterizedTest
    @ValueSource(longs = {16, 24, 32, 48})
    void tick_generatedLoad_keepsWhatTheHubAccountsWithinTheBudgetAtEveryEpoch(long megabytes) {
        var budget = MemoryBudget.of(megabytes * MEGABYTE);
        var largest = new long[1];

        var health = run(budget, epoch -> largest[0] = Math.max(largest[0], epoch.memory().bytes()));

        assertThat(largest[0]).isLessThanOrEqualTo(budget.total());
        assertThat(health.memory().budget()).isEqualTo(budget.total());
        assertThat(health.applications()).anySatisfy(application -> assertThat(application.count(HealthReason.EVICTED)).isPositive());
    }

    @Test
    void tick_budgetBelowWhatTheLoadNeeds_dropsSpecimensWhileTheReservoirsTheJoinAndCountingStayWhole() {
        var health = run(Budgets.total(60 * MEGABYTE), ignored -> {
        });

        assertThat(health.applications()).anySatisfy(application -> assertThat(application.count(HealthReason.EVICTED)).isPositive());
        assertThat(health.applications()).allSatisfy(application -> {
            assertThat(application.slowestK()).isEqualTo(5);
            assertThat(application.failuresK()).isEqualTo(20);
            assertThat(application.effectiveHorizon()).isEqualTo(PROFILE.hubConfig().build().linkHorizon());
        });
        assertCountingWhole(health);
    }

    @Test
    void tick_budgetSmaller_lowersTheReservoirsToTheirFloorBeforeTheJoinOrCountingGiveWay() {
        var health = run(Budgets.total(24 * MEGABYTE), ignored -> {
        });

        assertThat(health.applications()).anySatisfy(application -> assertThat(application).extracting(ApplicationHealth::slowestK, ApplicationHealth::failuresK)
            .containsExactly(0, 1));
        assertThat(health.applications()).allSatisfy(application -> assertThat(application.effectiveHorizon()).isEqualTo(Duration.ofSeconds(60)));
        assertCountingWhole(health);
    }

    @Test
    void tick_budgetSmallerStill_shortensTheEffectiveHorizonBeforeCountingGivesWay() {
        var health = run(Budgets.total(16 * MEGABYTE), ignored -> {
        });

        assertThat(health.applications()).anySatisfy(application -> assertThat(application.effectiveHorizon()).isLessThan(Duration.ofSeconds(60)));
        assertCountingWhole(health);
    }

    @Test
    void tick_budgetSmallest_foldsCountsSoonerAndShortensTheHistoryButKeepsTheWindow() {
        var largest = new long[1];

        var health = run(Budgets.total(6 * MEGABYTE), epoch -> largest[0] = Math.max(largest[0], epoch.memory().bytes()));

        assertThat(health.foldsSooner()).isTrue();
        assertThat(health.effectiveHistory()).isLessThan(PROFILE.history()).isGreaterThanOrEqualTo(PROFILE.window());
        assertThat(largest[0]).isLessThanOrEqualTo(Budgets.total(6 * MEGABYTE).total());
    }

    @Test
    @Timeout(10)
    void tick_budgetSmallerThanCountingAlone_dropsEverythingElseAndKeepsTheWindowsCounts() {
        var tickets = new RecordingInstance(TICKETS, "tickets-1");
        var hub = new SynchronousHub(HubConfig.withDefaults().build(), clock, Budgets.total(1));

        tickets.reportTo(hub, tickets.dispatch(OPEN));

        var health = hub.health();
        assertThat(health.application(TICKETS).orElseThrow().retained()).isZero();
        assertThat(health.memory().join().bytes()).isZero();
        assertThat(health.foldsSooner()).isTrue();
        assertThat(health.effectiveHistory()).isEqualTo(HubConfig.DEFAULT_WINDOW);
        assertThat(hub.statistics().application(TICKETS).orElseThrow().dispatches().started()).isEqualTo(1);
    }

    // TR9: the generated applications flood the hub while billing sends one span a second, well within its fair share.
    @Test
    void tick_otherApplicationsFlood_keepsWhatAQuietApplicationRetainsAndLinksBy() {
        var billing = new RecordingInstance(BILLING, "billing-1");
        var quiet = new long[1];

        var health = run(Budgets.total(20 * MEGABYTE), hub -> {
            billing.reportTo(hub, with(billing.dispatch(OPEN), clock.instant(), new Outcome.Completed(Duration.ofMillis(1))));
            quiet[0]++;
        }, ignored -> {
        });

        var application = health.application(BILLING).orElseThrow();
        assertThat(application.count(HealthReason.EVICTED)).isZero();
        assertThat(application.retained()).isEqualTo(quiet[0]);
        assertThat(application.completeSince()).isEmpty();
        assertThat(application.effectiveHorizon()).isEqualTo(Duration.ofSeconds(60));
        assertThat(application.specimens().bytes()).isLessThanOrEqualTo(application.specimens().limit());
        assertThat(health.applications()).anySatisfy(flooding -> assertThat(flooding.count(HealthReason.EVICTED)).isPositive());
    }

    private void assertCountingWhole(HealthView health) {
        assertThat(health.foldsSooner()).isFalse();
        assertThat(health.effectiveHistory()).isEqualTo(PROFILE.history());
    }

    private HealthView run(MemoryBudget budget, Consumer<HealthView> everyEpoch) {
        return run(budget, ignored -> {
        }, everyEpoch);
    }

    private HealthView run(MemoryBudget budget, Consumer<SynchronousHub> everySecond, Consumer<HealthView> everyEpoch) {
        var load = new LoadGenerator(PROFILE, SEED, clock);
        var hub = new SynchronousHub(PROFILE.hubConfig().build(), clock, budget);
        for (var second = 0; second < PROFILE.history().toSeconds() + 20; second++) {
            clock.advance(Duration.ofSeconds(1));
            load.export().forEach(hub::receive);
            everySecond.accept(hub);
            everyEpoch.accept(hub.health());
        }
        return hub.health();
    }
}
