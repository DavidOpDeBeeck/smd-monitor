package app.dodb.smd.monitor.hub;

import app.dodb.smd.monitor.hub.catalog.Catalog;
import app.dodb.smd.monitor.hub.catalog.CausalLink;
import app.dodb.smd.monitor.hub.catalog.Crossing;
import app.dodb.smd.monitor.hub.catalog.EventRoute;
import app.dodb.smd.monitor.hub.catalog.GroupLink;
import app.dodb.smd.monitor.hub.catalog.RaisedFailure;
import app.dodb.smd.monitor.hub.catalog.SinkRoute;
import app.dodb.smd.monitor.hub.counting.CountedInvocation;
import app.dodb.smd.monitor.hub.counting.Counters;
import app.dodb.smd.monitor.hub.counting.Table;
import app.dodb.smd.monitor.hub.intake.ReportingInstance;
import app.dodb.smd.monitor.hub.join.Link;
import app.dodb.smd.monitor.hub.span.InstanceName;
import app.dodb.smd.monitor.hub.span.MessageKind;
import app.dodb.smd.monitor.hub.span.MessageSpan;
import app.dodb.smd.monitor.hub.span.Outcome;
import app.dodb.smd.monitor.hub.span.SinkSend;
import app.dodb.smd.monitor.hub.span.Stage;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.IntFunction;
import java.util.function.ToIntFunction;
import java.util.stream.IntStream;

import static java.util.Objects.requireNonNull;

// What the hub observes of an application's spans, counted by the keys of its catalog: each invocation under its stage, instance, and failure;
// each link under its link, crossing, group link, route, and event delay; and each send under its sink route. It reads the statistics back
// from the running totals, so building them costs the same whatever the history.
final class ApplicationCounting {

    private final Catalog catalog;
    private final Counters counters;
    private Instant arrival;
    private final Map<Integer, Optional<Duration>> slowThresholds = new HashMap<>();

    ApplicationCounting(HubConfig config, Instant now) {
        this.catalog = new Catalog(requireNonNull(config));
        this.counters = new Counters(config, now);
        this.arrival = requireNonNull(now);
    }

    // Spans observed from now on arrived at the given instant.
    void advance(Instant now) {
        if (now.getEpochSecond() != arrival.getEpochSecond()) {
            slowThresholds.clear();
        }
        arrival = now;
        counters.advance(now);
    }

    void invoked(InstanceName instanceName, MessageSpan span) {
        var stage = catalog.stage(span.stage());
        if (span.entryPoint()) {
            catalog.entered(span.stage().type());
        }
        if (span.stage() instanceof Stage.Handling handling) {
            handling.processingGroup().ifPresent(group -> catalog.delivered(group, span.delivery().orElseThrow()));
        }
        var failure = span.outcome() instanceof Outcome.Failed(var ignored, var failureType)
            ? OptionalInt.of(catalog.failure(new RaisedFailure(span.stage(), failureType)))
            : OptionalInt.empty();
        var dispatched = span.stage() instanceof Stage.Dispatch dispatch ? Optional.of(dispatch.type().kind()) : Optional.<MessageKind>empty();
        var instance = catalog.instance(instanceName);
        counters.countInvocation(new CountedInvocation(span.startedAt(), span.outcome().duration(), stage, dispatched, instance, span.entryPoint(), failure));
        counters.recordArrival(arrival, span.startedAt(), span.outcome().duration(), instance);
    }

    // Slower than the p95 of its stage over the window, as of the second the counters moved to last (P8).
    boolean slow(MessageSpan span) {
        var stage = catalog.stage(span.stage());
        var threshold = slowThresholds.computeIfAbsent(stage, id -> counters.windowLatency(Table.STAGE, id).percentile(0.95));
        return threshold.isPresent() && span.outcome().duration().compareTo(threshold.get()) > 0;
    }

    // Counted in the start second of the child: a dispatch under its link within the application, its crossing from another, or the group
    // link from a processing group's handling; a processing group's handling under the route of its event and the delay after its publication.
    void linked(Link linked) {
        var child = linked.child();
        var parent = linked.parent();
        var startedAt = child.startedAt();
        if (child.stage() instanceof Stage.Dispatch) {
            var link = new CausalLink(parent.type(), child.type());
            if (linked.local() && !linked.delivered()) {
                counters.countOccurrence(Table.LINK, catalog.link(link), startedAt);
            }
            if (!linked.local()) {
                counters.countOccurrence(Table.CROSSING, catalog.crossing(new Crossing(parent.application(), link)), startedAt);
            }
            if (linked.local() && parent.processingGroup().isPresent()) {
                counters.countOccurrence(Table.GROUP_LINK, catalog.groupLink(new GroupLink(parent.processingGroup().get(), child.type())), startedAt);
            }
            return;
        }
        var group = child.processingGroup();
        if (group.isEmpty()) {
            return;
        }
        counters.countOccurrence(Table.EVENT_ROUTE, catalog.route(new EventRoute(parent.application(), child.type(), group.get())), startedAt);
        var delay = Duration.between(parent.completedAt(), startedAt);
        if (!delay.isNegative()) {
            var stage = catalog.stage(child.stage());
            catalog.ranAsynchronously(stage);
            counters.countDelay(stage, startedAt, delay);
        }
    }

    void sent(SinkSend send) {
        counters.countSend(catalog.sinkRoute(new SinkRoute(send.event(), send.delivery())), send.startedAt());
    }

    Instant end() {
        return Instant.ofEpochSecond(counters.end());
    }

    Instant provisionalFrom() {
        return Instant.ofEpochSecond(counters.provisionalFrom());
    }

    long revised() {
        return counters.revised();
    }

    long beyondHistory() {
        return counters.beyondHistory();
    }

    long overflowed() {
        return catalog.overflowed();
    }

    // The heap the counts and the catalog take.
    long bytes() {
        return counters.bytes() + catalog.bytes();
    }

    void limit(boolean foldSooner, Duration history) {
        counters.limit(foldSooner, history);
    }

    // The figures of every stage, read once: the types, the processing groups, and the sums per kind and over the handlings.
    StageFigures stages(ToIntFunction<Stage> retained) {
        var types = new ArrayList<TypeStatistics>();
        var handlings = new LinkedHashMap<String, List<HandlingStatistics>>();
        var handlingsByGroup = new HashMap<String, IntStream.Builder>();
        var dispatchesByKind = new EnumMap<MessageKind, IntStream.Builder>(MessageKind.class);
        for (var kind : MessageKind.values()) {
            dispatchesByKind.put(kind, IntStream.builder().add(Catalog.otherDispatch(kind)));
        }
        var allHandlings = IntStream.builder().add(Catalog.OTHER_HANDLING);
        var ungroupedHandlings = IntStream.builder().add(Catalog.OTHER_HANDLING);
        for (var id = catalog.firstStage(); id < catalog.stagesEnd(); id++) {
            var stage = catalog.stageOf(id);
            if (stage instanceof Stage.Dispatch dispatch) {
                dispatchesByKind.get(dispatch.type().kind()).add(id);
                types.add(new TypeStatistics(dispatch.type(), catalog.entryPoint(dispatch.type()), counters.window(Table.STAGE, id),
                    counters.history(Table.STAGE, id), retained.applyAsInt(stage), counters.startedPerSecond(id), counters.failedPerSecond(id)));
                continue;
            }
            allHandlings.add(id);
            if (stage instanceof Stage.Handling handling && handling.processingGroup().isPresent()) {
                var group = handling.processingGroup().get();
                handlings.computeIfAbsent(group, ignored -> new ArrayList<>())
                    .add(new HandlingStatistics(handling.type(), counters.window(Table.STAGE, id), counters.history(Table.STAGE, id),
                        counters.windowLatency(Table.DELAY, id), catalog.asynchronous(id)));
                handlingsByGroup.computeIfAbsent(group, ignored -> IntStream.builder()).add(id);
            } else {
                ungroupedHandlings.add(id);
            }
        }
        var groups = handlings.entrySet().stream()
            .map(group -> new GroupStatistics(group.getKey(), catalog.delivery(group.getKey()),
                counters.windowSum(Table.STAGE, handlingsByGroup.get(group.getKey()).build().toArray()), group.getValue()))
            .toList();
        var kinds = new EnumMap<MessageKind, Counts>(MessageKind.class);
        dispatchesByKind.forEach((kind, ids) -> kinds.put(kind, counters.windowSum(Table.STAGE, ids.build().toArray())));
        // The handlings sum the groups' sums and the handlings of no group, so each handling's histogram is added once.
        var handlingCounts = groups.stream().map(GroupStatistics::window).reduce(counters.windowSum(Table.STAGE, ungroupedHandlings.build().toArray()), Counts::plus);
        return new StageFigures(types, groups, kinds, handlingCounts, counters.windowSum(Table.DELAY, allHandlings.build().toArray()).latency());
    }

    List<LinkStatistics> links() {
        return edges(catalog.linksEnd(), id -> new LinkStatistics(catalog.linkOf(id), counters.historyCount(Table.LINK, id), counters.lastSecond(Table.LINK, id)));
    }

    List<CrossingStatistics> crossings() {
        return edges(catalog.crossingsEnd(), id -> {
            var crossing = catalog.crossingOf(id);
            return new CrossingStatistics(crossing.parentApplication(),
                new LinkStatistics(crossing.link(), counters.historyCount(Table.CROSSING, id), counters.lastSecond(Table.CROSSING, id)));
        });
    }

    List<GroupLinkStatistics> groupLinks() {
        return edges(catalog.groupLinksEnd(),
            id -> new GroupLinkStatistics(catalog.groupLinkOf(id), counters.historyCount(Table.GROUP_LINK, id), counters.lastSecond(Table.GROUP_LINK, id)));
    }

    List<EventRouteStatistics> routes() {
        return edges(catalog.routesEnd(),
            id -> new EventRouteStatistics(catalog.routeOf(id), counters.historyCount(Table.EVENT_ROUTE, id), counters.lastSecond(Table.EVENT_ROUTE, id)));
    }

    List<SinkRouteStatistics> sinkRoutes() {
        return edges(catalog.sinkRoutesEnd(),
            id -> new SinkRouteStatistics(catalog.sinkRouteOf(id), counters.historyCount(Table.SINK_ROUTE, id), counters.lastSecond(Table.SINK_ROUTE, id)));
    }

    List<RaisedFailureStatistics> failures() {
        var perSecond = counters.windowPerSecond(Table.FAILURE, catalog.failuresEnd());
        return edges(catalog.failuresEnd(), id -> new RaisedFailureStatistics(catalog.failureOf(id), counters.windowCount(Table.FAILURE, id),
            counters.historyCount(Table.FAILURE, id), perSecond.get(id)));
    }

    OtherStatistics other() {
        var dispatchWindow = Counts.NONE;
        var dispatchHistory = Counts.NONE;
        for (var kind : MessageKind.values()) {
            dispatchWindow = dispatchWindow.plus(counters.window(Table.STAGE, Catalog.otherDispatch(kind)));
            dispatchHistory = dispatchHistory.plus(counters.history(Table.STAGE, Catalog.otherDispatch(kind)));
        }
        var edges = 0L;
        for (var table : List.of(Table.LINK, Table.CROSSING, Table.GROUP_LINK, Table.EVENT_ROUTE, Table.SINK_ROUTE)) {
            edges += counters.historyCount(table, Catalog.OTHER);
        }
        return new OtherStatistics(dispatchWindow, dispatchHistory, counters.window(Table.STAGE, Catalog.OTHER_HANDLING),
            counters.history(Table.STAGE, Catalog.OTHER_HANDLING), edges, counters.historyCount(Table.FAILURE, Catalog.OTHER));
    }

    List<Long> enteredPerSecond() {
        return counters.enteredPerSecond();
    }

    Map<MessageKind, List<Long>> startedPerSecond() {
        var started = new EnumMap<MessageKind, List<Long>>(MessageKind.class);
        for (var kind : MessageKind.values()) {
            started.put(kind, counters.startedPerSecond(kind));
        }
        return started;
    }

    List<Long> failedPerSecond() {
        return counters.failedPerSecond();
    }

    List<Instance> instances(List<ReportingInstance> reporting) {
        return reporting.stream()
            .map(instance -> {
                var id = catalog.instance(instance.name());
                return new Instance(instance.name(), instance.session(), instance.lastReportedAt(), instance.silent(), instance.clockAhead(),
                    counters.window(Table.INSTANCE_DISPATCH, id), counters.window(Table.INSTANCE_HANDLING, id), counters.exportLag(id));
            })
            .toList();
    }

    private static <S> List<S> edges(int end, IntFunction<S> statistics) {
        return IntStream.range(Catalog.OTHER + 1, end).mapToObj(statistics).toList();
    }

    // The dispatches of every kind and the handlings sum the stages beyond the caps too; handlings include those of commands and queries, which
    // have no processing group, and the event delay is over every processing group's handling of the window.
    record StageFigures(List<TypeStatistics> types, List<GroupStatistics> groups, Map<MessageKind, Counts> kinds, Counts handlings, LatencyHistogram eventDelay) {

        StageFigures {
            requireNonNull(types);
            requireNonNull(groups);
            requireNonNull(kinds);
            requireNonNull(handlings);
            requireNonNull(eventDelay);
        }
    }
}
