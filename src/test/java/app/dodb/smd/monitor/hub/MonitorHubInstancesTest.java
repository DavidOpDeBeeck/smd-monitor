package app.dodb.smd.monitor.hub;

import app.dodb.smd.monitor.hub.RecordingInstance.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static app.dodb.smd.monitor.hub.RecordingInstance.ASSIGN;
import static app.dodb.smd.monitor.hub.RecordingInstance.BILLING;
import static app.dodb.smd.monitor.hub.RecordingInstance.OPEN;
import static app.dodb.smd.monitor.hub.RecordingInstance.OPENED;
import static app.dodb.smd.monitor.hub.RecordingInstance.TICKETS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class MonitorHubInstancesTest {

    private final MutableClock clock = new MutableClock();
    private final SynchronousHub hub = new SynchronousHub(HubConfig.withDefaults().maxInstances(3).build(), clock);
    private final RecordingInstance first = new RecordingInstance(TICKETS, "tickets-1");

    @Test
    void statistics_instancesWithTraffic_summarizeTheWindowOfEach() {
        var second = new RecordingInstance(TICKETS, "tickets-2");
        first.reportTo(hub, first.dispatch(OPEN), first.dispatch(OPEN));
        var open = second.dispatch(OPEN);
        var publication = second.dispatch(OPENED, open);
        second.reportTo(hub, publication, open, second.handle("notifications", publication));

        var instances = instances();

        assertThat(instances).extracting(Instance::name, instance -> instance.dispatches().started(), instance -> instance.handlings().started())
            .containsExactly(tuple(first.name, 2L, 0L), tuple(second.name, 2L, 1L));
        assertThat(instances.getFirst().dispatches().completed()).isEqualTo(2);
    }

    @Test
    void statistics_trafficOlderThanTheWindow_isLeftOutOfTheSummary() {
        first.reportTo(hub, first.dispatch(OPEN));

        clock.advance(Duration.ofSeconds(61));

        assertThat(instances()).singleElement().satisfies(instance -> assertThat(instance.dispatches().started()).isZero());
    }

    @Test
    void statistics_restartedInstance_listsItsNewSessionAndCountsTheEarlierOne() {
        first.reportTo(hub, first.dispatch(OPEN));
        var restarted = new RecordingInstance(TICKETS, "tickets-1");

        restarted.reportTo(hub, restarted.dispatch(OPEN));

        assertThat(instances()).extracting(Instance::session, instance -> instance.dispatches().started()).containsExactly(tuple(restarted.session, 2L));
    }

    @Test
    void statistics_instanceSilentForTenSeconds_isSilent() {
        first.reportTo(hub, first.dispatch(OPEN));

        clock.advance(Duration.ofSeconds(10));

        assertThat(instances()).singleElement().satisfies(instance -> assertThat(instance.silent()).isTrue());
    }

    @Test
    void statistics_configuredSilentAfter_marksTheInstanceSilentThen() {
        var hub = new SynchronousHub(HubConfig.withDefaults().silentAfter(Duration.ofSeconds(30)).build(), clock);
        first.reportTo(hub, first.dispatch(OPEN));
        clock.advance(Duration.ofSeconds(29));
        var reporting = hub.statistics().application(TICKETS).orElseThrow().instances();

        clock.advance(Duration.ofSeconds(1));

        assertThat(reporting).singleElement().satisfies(instance -> assertThat(instance.silent()).isFalse());
        assertThat(hub.statistics().application(TICKETS).orElseThrow().instances()).singleElement().satisfies(instance -> assertThat(instance.silent()).isTrue());
    }

    @Test
    void receive_silentInstanceReportingAgain_isReporting() {
        first.reportTo(hub, first.dispatch(OPEN));
        clock.advance(Duration.ofSeconds(10));

        first.reportTo(hub, first.dispatch(OPEN));

        assertThat(instances()).singleElement().satisfies(instance -> assertThat(instance.silent()).isFalse());
    }

    @Test
    void statistics_instanceSilentForTenMinutesBesideAReportingOne_isRetiredButItsTypesRemain() {
        var second = new RecordingInstance(TICKETS, "tickets-2");
        first.reportTo(hub, first.dispatch(OPEN));
        clock.advance(Duration.ofMinutes(9));
        second.reportTo(hub, second.dispatch(OPENED));

        clock.advance(Duration.ofMinutes(1));

        assertThat(instances()).extracting(Instance::name).containsExactly(second.name);
        assertThat(hub.statistics().application(TICKETS).orElseThrow().types()).extracting(TypeStatistics::type).containsExactlyInAnyOrder(OPEN, OPENED);
    }

    @Test
    void statistics_everyInstanceSilentForTenMinutes_retiresTheApplicationWithWhatTheHubRetained() {
        first.reportTo(hub, first.dispatch(OPEN));

        clock.advance(Duration.ofMinutes(10));

        assertThat(hub.statistics().applications()).isEmpty();
        assertThat(hub.retained().invocations()).isEmpty();
    }

    @Test
    void statistics_configuredRetireAfter_retiresTheApplicationThen() {
        var hub = new SynchronousHub(HubConfig.withDefaults().retireAfter(Duration.ofMinutes(1)).build(), clock);
        first.reportTo(hub, first.dispatch(OPEN));
        clock.advance(Duration.ofSeconds(59));
        var reporting = hub.statistics().applications();

        clock.advance(Duration.ofSeconds(1));

        assertThat(reporting).extracting(ApplicationStatistics::application).containsExactly(TICKETS);
        assertThat(hub.statistics().applications()).isEmpty();
    }

    @Test
    void receive_applicationRetired_freesItsPlaceForAnother() {
        var hub = new SynchronousHub(HubConfig.withDefaults().maxApplications(1).build(), clock);
        first.reportTo(hub, first.dispatch(OPEN));
        clock.advance(Duration.ofMinutes(10));
        hub.epoch();
        var billing = new RecordingInstance(BILLING, "billing-1");

        var refusal = billing.reportTo(hub, billing.dispatch(OPEN));

        assertThat(refusal).isEmpty();
        assertThat(hub.statistics().applications()).extracting(ApplicationStatistics::application).containsExactly(BILLING);
    }

    @Test
    void receive_retiredApplicationReportingAgain_startsWithoutItsEarlierInvocations() {
        first.reportTo(hub, first.dispatch(OPEN));
        clock.advance(Duration.ofMinutes(10));
        hub.epoch();
        var assign = first.dispatch(ASSIGN);

        first.reportTo(hub, assign);

        assertThat(hub.retained().invocations()).extracting(Invocation::span).containsExactly(assign);
        assertThat(instances()).extracting(Instance::name).containsExactly(first.name);
    }

    @Test
    void receive_newInstanceBeyondTheLimitWhileNoneIsSilent_isRejectedWithContext() {
        reportFromEveryInstanceAllowed();
        var oneTooMany = new RecordingInstance(TICKETS, "one-too-many");

        var refusal = oneTooMany.reportTo(hub, oneTooMany.dispatch(OPEN));

        assertThat(refusal).hasValueSatisfying(refused -> {
            assertThat(refused.spans()).isEqualTo(1);
            assertThat(refused.reason()).contains("ticket-service", "already has 3 reporting instances", "one-too-many");
        });
        assertThat(instances()).extracting(instance -> instance.name().value()).doesNotContain("one-too-many");
    }

    @Test
    void receive_newInstanceBeyondTheLimit_retiresTheLongestSilentInstance() {
        reportFromEveryInstanceAllowed();
        clock.advance(Duration.ofSeconds(10));

        new RecordingInstance(TICKETS, "one-too-many").reportTo(hub);

        assertThat(instances()).extracting(instance -> instance.name().value()).containsExactly("one-too-many", "tickets-1", "tickets-2");
    }

    private void reportFromEveryInstanceAllowed() {
        for (var index = 0; index < 3; index++) {
            new RecordingInstance(TICKETS, "tickets-" + index).reportTo(hub);
        }
    }

    private List<Instance> instances() {
        return hub.statistics().application(TICKETS).orElseThrow().instances();
    }
}
