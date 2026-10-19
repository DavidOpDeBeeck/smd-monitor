package app.dodb.smd.monitor.hub;

import app.dodb.smd.api.event.delivery.EventDelivery;
import app.dodb.smd.monitor.hub.RecordingInstance.MutableClock;
import app.dodb.smd.monitor.hub.budget.Budgets;
import app.dodb.smd.monitor.hub.catalog.CausalLink;
import app.dodb.smd.monitor.hub.catalog.EventRoute;
import app.dodb.smd.monitor.hub.catalog.GroupLink;
import app.dodb.smd.monitor.hub.health.HealthReason;
import app.dodb.smd.monitor.hub.intake.SpanBatch;
import app.dodb.smd.monitor.hub.span.ApplicationName;
import app.dodb.smd.monitor.hub.span.MessageKind;
import app.dodb.smd.monitor.hub.span.MessageSpan;
import app.dodb.smd.monitor.hub.span.MessageType;
import app.dodb.smd.monitor.hub.span.Outcome;
import app.dodb.smd.monitor.hub.span.SinkSend;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.stream.Collectors;

import static app.dodb.smd.monitor.hub.RecordingInstance.ASSIGN;
import static app.dodb.smd.monitor.hub.RecordingInstance.BILLING;
import static app.dodb.smd.monitor.hub.RecordingInstance.OPEN;
import static app.dodb.smd.monitor.hub.RecordingInstance.OPENED;
import static app.dodb.smd.monitor.hub.RecordingInstance.STARTED_AT;
import static app.dodb.smd.monitor.hub.RecordingInstance.TICKETS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

// The behavior every slice of docs/monitor-hub-design.md keeps: linking in any arrival order, within and across applications (TR2), deliveries
// of the same message, counts that do not depend on eviction (TR3), and each span counted once (TR1).
class MonitorHubInvariantsTest {

    private static final LoadProfile PROFILE = new LoadProfile(3, 2, 15, 4, 200, Duration.ofMinutes(1), Duration.ofMinutes(5), HubConfig.DEFAULT_MEMORY);
    private static final int SECONDS = 30;

    private final MutableClock clock = new MutableClock();
    private final RecordingInstance tickets = new RecordingInstance(TICKETS, "tickets-1");
    private final RecordingInstance otherTickets = new RecordingInstance(TICKETS, "tickets-2");
    private final RecordingInstance billing = new RecordingInstance(BILLING, "billing-1");

    @Test
    void statistics_generatedLoadInAnyArrivalOrder_countsTheSame() {
        var reports = generated(11);
        var expected = counted(receivedBy(hub(), reports));

        var orders = List.of(reversed(reports), reordered(reports, 1), reordered(reports, 2), reordered(reports, 3));

        assertThat(expected).allSatisfy(application -> {
            assertThat(application.links()).isNotEmpty();
            assertThat(application.crossings()).isNotEmpty();
            assertThat(application.routes()).isNotEmpty();
        });
        assertThat(orders).allSatisfy(order -> assertThat(counted(receivedBy(hub(), order))).isEqualTo(expected));
    }

    @Test
    void trace_chainAcrossApplicationsInAnyArrivalOrder_isOneTraceFromItsEntryPoint() {
        var open = tickets.dispatch(OPEN);
        var handling = tickets.handle(open);
        var assign = billing.dispatch(ASSIGN, handling);
        var chain = List.of(new Arrival(tickets, open), new Arrival(tickets, handling), new Arrival(billing, assign), new Arrival(billing, billing.handle(assign)));

        assertThat(permutations(chain)).allSatisfy(order -> {
            var hub = receivedEach(order);

            assertThat(traces(hub)).singleElement().satisfies(trace -> {
                assertThat(trace.root().span()).isEqualTo(open);
                assertThat(trace.size()).isEqualTo(4);
                assertThat(trace.applications()).containsExactlyInAnyOrder(TICKETS, BILLING);
            });
            assertThat(application(hub, BILLING).crossings()).containsExactly(new CrossingStatistics(TICKETS, new LinkStatistics(new CausalLink(OPEN, ASSIGN), 1, 0)));
            assertThat(application(hub, TICKETS).types()).extracting(TypeStatistics::type, TypeStatistics::entryPoint).containsExactly(tuple(OPEN, true));
            assertThat(application(hub, BILLING).types()).extracting(TypeStatistics::type, TypeStatistics::entryPoint).containsExactly(tuple(ASSIGN, false));
        });
    }

    @Test
    void statistics_eventStoreHandlingOnAnotherInstanceInAnyArrivalOrder_countsTheRouteGroupLinkAndDelay() {
        var publication = tickets.dispatch(OPENED);
        var handling = RecordingInstance.with(otherTickets.handle("projection", new EventDelivery("event-store"), publication), STARTED_AT.plusSeconds(1),
            new Outcome.Completed(Duration.ofMillis(1)));
        var assign = RecordingInstance.with(otherTickets.dispatch(ASSIGN, handling), STARTED_AT.plusSeconds(1), new Outcome.Completed(Duration.ofMillis(1)));
        var chain = List.of(new Arrival(tickets, publication), new Arrival(otherTickets, handling), new Arrival(otherTickets, assign));

        assertThat(permutations(chain)).allSatisfy(order -> {
            var hub = receivedEach(order);

            var statistics = application(hub, TICKETS);
            assertThat(statistics.routes()).extracting(EventRouteStatistics::route, EventRouteStatistics::history)
                .containsExactly(tuple(new EventRoute(TICKETS, OPENED, "projection"), 1L));
            assertThat(statistics.groupLinks()).extracting(GroupLinkStatistics::link, GroupLinkStatistics::history)
                .containsExactly(tuple(new GroupLink("projection", ASSIGN), 1L));
            assertThat(statistics.links()).extracting(LinkStatistics::link, LinkStatistics::history).containsExactly(tuple(new CausalLink(OPENED, ASSIGN), 1L));
            assertThat(statistics.groups()).singleElement().satisfies(group -> assertThat(group.handlings()).singleElement().satisfies(each -> {
                assertThat(each.asynchronous()).isTrue();
                assertThat(each.delay().count()).isEqualTo(1);
            }));
            assertThat(traces(hub)).singleElement().satisfies(trace -> assertThat(trace.size()).isEqualTo(3));
        });
    }

    @Test
    void trace_eventDeliveredFromAnotherApplicationInAnyArrivalOrder_namesTheOriginAsItsDeliveredParent() {
        var publication = tickets.dispatch(OPENED);
        var delivery = billing.republish(publication);
        var chain = List.of(new Arrival(tickets, publication), new Arrival(billing, delivery), new Arrival(billing, billing.handle("ledger", delivery)));

        assertThat(permutations(chain)).allSatisfy(order -> {
            var hub = receivedEach(order);

            var trace = traces(hub).getFirst();
            assertThat(hub.trace(trace.root().id()).orElseThrow().members())
                .extracting(member -> member.invocation().span(), member -> member.parent().map(ParentSummary::application),
                    member -> member.parent().map(ParentSummary::delivered))
                .containsExactlyInAnyOrder(
                    tuple(publication, Optional.empty(), Optional.empty()),
                    tuple(delivery, Optional.of(TICKETS), Optional.of(true)),
                    tuple(chain.get(2).span(), Optional.of(BILLING), Optional.of(false)));
            assertThat(application(hub, BILLING).crossings())
                .containsExactly(new CrossingStatistics(TICKETS, new LinkStatistics(new CausalLink(OPENED, OPENED), 1, 0)));
            assertThat(application(hub, BILLING).routes()).containsExactly(new EventRouteStatistics(new EventRoute(BILLING, OPENED, "ledger"), 1, 0));
        });
    }

    @Test
    void statistics_specimensEvictingAlmostEveryInvocation_countsAsWithoutEviction() {
        var reports = generated(12);
        var expected = counted(receivedBy(hub(), reports));

        var evicting = receivedBy(new SynchronousHub(PROFILE.hubConfig().build(), clock, Budgets.retaining(1)), reports);

        assertThat(evicting.health().applications()).allSatisfy(application -> assertThat(application.count(HealthReason.EVICTED)).isPositive());
        assertThat(counted(evicting)).isEqualTo(expected);
    }

    @Test
    void statistics_everySpanReceivedTwice_countsEachOnce() {
        var reports = generated(13);
        var expected = counted(receivedBy(hub(), reports));
        var twice = new ArrayList<>(reports);
        twice.addAll(reordered(reports, 4));

        var counted = counted(receivedBy(hub(), twice));

        assertThat(counted).isEqualTo(expected);
    }

    // Duplicates are recognized by the span index within the link horizon, whatever the specimens evicted (TR1, TR3).
    @Test
    void statistics_everySpanAndSendReceivedTwiceRetainingAboutOne_countsEachOnce() {
        var reports = generated(14);
        var expected = counted(receivedBy(hub(), reports));
        var twice = new ArrayList<>(reports);
        twice.addAll(reordered(reports, 5));

        var counted = counted(receivedBy(new SynchronousHub(PROFILE.hubConfig().build(), clock, Budgets.retaining(1)), twice));

        assertThat(counted).isEqualTo(expected);
    }

    @Test
    void statistics_spanReceivedTwiceInOneReport_countsItOnce() {
        var open = tickets.dispatch(OPEN);
        var hub = hub();

        tickets.reportTo(hub, open, tickets.dispatch(ASSIGN, open), open);

        assertThat(application(hub, TICKETS).types()).extracting(TypeStatistics::type, type -> type.history().started())
            .containsExactlyInAnyOrder(tuple(OPEN, 1L), tuple(ASSIGN, 1L));
        assertThat(application(hub, TICKETS).links()).containsExactly(new LinkStatistics(new CausalLink(OPEN, ASSIGN), 1, 0));
    }

    @Test
    void statistics_childReceivedAgainAfterItsParent_countsTheLinkOnce() {
        var open = tickets.dispatch(OPEN);
        var assign = tickets.dispatch(ASSIGN, open);
        var hub = hub();
        tickets.reportTo(hub, assign);
        tickets.reportTo(hub, open);

        tickets.reportTo(hub, assign);

        assertThat(application(hub, TICKETS).links()).containsExactly(new LinkStatistics(new CausalLink(OPEN, ASSIGN), 1, 0));
        assertThat(application(hub, TICKETS).dispatches().started()).isEqualTo(2);
    }

    private SynchronousHub hub() {
        return new SynchronousHub(PROFILE.hubConfig().build(), clock);
    }

    // The reports the generator exports over the seconds, received when the last of them was exported.
    private List<SpanBatch> generated(long seed) {
        var load = new LoadGenerator(PROFILE, seed, clock);
        var reports = new ArrayList<SpanBatch>();
        for (var second = 0; second < SECONDS; second++) {
            clock.advance(Duration.ofSeconds(1));
            reports.addAll(load.export());
        }
        return reports;
    }

    private static SynchronousHub receivedBy(SynchronousHub hub, List<SpanBatch> reports) {
        reports.forEach(report -> assertThat(hub.receive(report)).isEmpty());
        return hub;
    }

    private SynchronousHub receivedEach(List<Arrival> order) {
        var hub = hub();
        order.forEach(arrival -> arrival.instance().reportTo(hub, arrival.span()));
        return hub;
    }

    // The last report first, each with its spans and sends in reverse, so children mostly arrive before their parents.
    private static List<SpanBatch> reversed(List<SpanBatch> reports) {
        return reports.reversed().stream()
            .map(report -> new SpanBatch(report.application(), report.instance(), report.session(), report.spans().reversed(), report.sends().reversed()))
            .toList();
    }

    // The same spans and sends shuffled across reports and instances, in reports of one to ten of one instance.
    private static List<SpanBatch> reordered(List<SpanBatch> reports, long seed) {
        var random = new SplittableRandom(seed);
        var items = new ArrayList<Item>();
        for (var report : reports) {
            report.spans().forEach(span -> items.add(new Item(report, Optional.of(span), Optional.empty())));
            report.sends().forEach(send -> items.add(new Item(report, Optional.empty(), Optional.of(send))));
        }
        Collections.shuffle(items, random);
        var reordered = new ArrayList<SpanBatch>();
        var index = 0;
        while (index < items.size()) {
            var source = items.get(index).source();
            var size = 1 + random.nextInt(10);
            var spans = new ArrayList<MessageSpan>();
            var sends = new ArrayList<SinkSend>();
            while (index < items.size() && spans.size() + sends.size() < size && items.get(index).sameInstance(source)) {
                items.get(index).span().ifPresent(spans::add);
                items.get(index).send().ifPresent(sends::add);
                index++;
            }
            reordered.add(new SpanBatch(source.application(), source.instance(), source.session(), spans, sends));
        }
        return reordered;
    }

    private static List<Trace> traces(SynchronousHub hub) {
        return hub.traces(new TraceQuery(Optional.empty(), TypeSelection.EVERY_TYPE, Set.of(), Set.of(), false, "", TraceOrder.NEWEST, Optional.empty(), 10))
            .traces();
    }

    private static ApplicationStatistics application(SynchronousHub hub, ApplicationName name) {
        return hub.statistics().application(name).orElseThrow();
    }

    private static <T> List<List<T>> permutations(List<T> items) {
        if (items.size() <= 1) {
            return List.of(items);
        }
        var permutations = new ArrayList<List<T>>();
        for (var first = 0; first < items.size(); first++) {
            var rest = new ArrayList<>(items);
            var head = rest.remove(first);
            for (var permutation : permutations(rest)) {
                var each = new ArrayList<T>();
                each.add(head);
                each.addAll(permutation);
                permutations.add(each);
            }
        }
        return permutations;
    }

    // What the hub counted of each application, apart from what it retains and in no particular order: the figures that survive eviction.
    private static Set<Counted> counted(SynchronousHub hub) {
        return hub.statistics().applications().stream().map(Counted::of).collect(Collectors.toSet());
    }

    private record Counted(ApplicationName application,
                           long failedReceived,
                           Set<Instance> instances,
                           Set<TypeCounts> types,
                           Set<HandlingCounts> handlingsByGroup,
                           Set<LinkStatistics> links,
                           Set<CrossingStatistics> crossings,
                           Set<EventRouteStatistics> routes,
                           Set<SinkRouteStatistics> sinkRoutes,
                           Set<GroupLinkStatistics> groupLinks,
                           Counts dispatches,
                           Counts handlings,
                           LatencyHistogram eventDelay,
                           Map<MessageKind, Counts> kinds,
                           List<Long> enteredPerSecond,
                           Map<MessageKind, List<Long>> startedPerSecond,
                           List<Long> failedPerSecond) {

        static Counted of(ApplicationStatistics statistics) {
            return new Counted(statistics.application(), statistics.failedReceived(), Set.copyOf(statistics.instances()),
                statistics.types().stream().map(type -> new TypeCounts(type.type(), type.entryPoint(), type.window(), type.history())).collect(Collectors.toSet()),
                statistics.groups().stream()
                    .flatMap(group -> group.handlings().stream().map(handling -> new HandlingCounts(group.processingGroup(), group.delivery(), handling)))
                    .collect(Collectors.toSet()),
                Set.copyOf(statistics.links()), Set.copyOf(statistics.crossings()), Set.copyOf(statistics.routes()), Set.copyOf(statistics.sinkRoutes()),
                Set.copyOf(statistics.groupLinks()), statistics.dispatches(), statistics.handlings(), statistics.eventDelay(), statistics.kinds(),
                statistics.enteredPerSecond(), statistics.startedPerSecond(), statistics.failedPerSecond());
        }
    }

    private record TypeCounts(MessageType type, boolean entryPoint, Counts window, Counts history) {
    }

    private record HandlingCounts(String processingGroup, EventDelivery delivery, HandlingStatistics handling) {
    }

    private record Arrival(RecordingInstance instance, MessageSpan span) {
    }

    private record Item(SpanBatch source, Optional<MessageSpan> span, Optional<SinkSend> send) {

        boolean sameInstance(SpanBatch report) {
            return source.application().equals(report.application()) && source.instance().equals(report.instance());
        }
    }
}
