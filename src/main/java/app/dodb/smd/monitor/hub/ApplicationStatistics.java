package app.dodb.smd.monitor.hub;

import app.dodb.smd.monitor.hub.span.ApplicationName;
import app.dodb.smd.monitor.hub.span.MessageKind;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static java.util.Objects.requireNonNull;

// The window and the longer history of an application. Every type, processing group, and link the application ever reported within its
// caps is listed, idle or not, and what went beyond them is summed as other; its crossings are the links into it from the types of other applications. Its routes lead the events of any application
// to its processing groups, its sink routes lead its own events to the deliveries of its sinks, and its group links lead its processing groups to
// the messages they dispatched. The handlings are those of the processing groups, as a command's or query's dispatch covers its handler. The
// failed received counts every failed invocation the hub received since it started, and the event delay covers every handling of the window. The seconds of the window from provisional-from on may still grow, as spans of the
// application arrive that late. What the hub refused, dropped, and revised is in its health.
public record ApplicationStatistics(ApplicationName application,
                                    long failedReceived,
                                    List<Instance> instances,
                                    List<TypeStatistics> types,
                                    List<GroupStatistics> groups,
                                    List<LinkStatistics> links,
                                    List<CrossingStatistics> crossings,
                                    List<EventRouteStatistics> routes,
                                    List<SinkRouteStatistics> sinkRoutes,
                                    List<GroupLinkStatistics> groupLinks,
                                    Counts dispatches,
                                    Counts handlings,
                                    LatencyHistogram eventDelay,
                                    Map<MessageKind, Counts> kinds,
                                    List<Long> enteredPerSecond,
                                    Map<MessageKind, List<Long>> startedPerSecond,
                                    List<Long> failedPerSecond,
                                    List<RaisedFailureStatistics> failures,
                                    OtherStatistics other,
                                    Instant provisionalFrom) {

    public ApplicationStatistics {
        requireNonNull(application);
        requireNonNull(instances);
        requireNonNull(types);
        requireNonNull(groups);
        requireNonNull(links);
        requireNonNull(crossings);
        requireNonNull(routes);
        requireNonNull(sinkRoutes);
        requireNonNull(groupLinks);
        requireNonNull(dispatches);
        requireNonNull(handlings);
        requireNonNull(eventDelay);
        requireNonNull(kinds);
        requireNonNull(enteredPerSecond);
        requireNonNull(startedPerSecond);
        requireNonNull(failedPerSecond);
        requireNonNull(failures);
        requireNonNull(other);
        requireNonNull(provisionalFrom);
    }

    // The other applications whose messages caused its invocations over the history, by name: through its crossings and the routes of their
    // events to its processing groups, as applications integrated by events link only by routes.
    public List<UpstreamStatistics> upstream() {
        var byApplication = new TreeMap<ApplicationName, UpstreamStatistics>(Comparator.comparing(ApplicationName::value));
        for (var crossing : crossings) {
            byApplication.merge(crossing.parentApplication(), new UpstreamStatistics(crossing.parentApplication(), crossing.link().history(), crossing.link().lastSecond()),
                UpstreamStatistics::plus);
        }
        for (var route : routes) {
            var publisher = route.route().publisher();
            if (!publisher.equals(application)) {
                byApplication.merge(publisher, new UpstreamStatistics(publisher, route.history(), route.lastSecond()), UpstreamStatistics::plus);
            }
        }
        return byApplication.values().stream().filter(upstream -> upstream.history() > 0).toList();
    }
}
