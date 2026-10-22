package app.dodb.smd.monitor.json;

import app.dodb.smd.monitor.hub.ApplicationStatistics;
import app.dodb.smd.monitor.hub.CrossingStatistics;
import app.dodb.smd.monitor.hub.EventRouteStatistics;
import app.dodb.smd.monitor.hub.GroupLinkStatistics;
import app.dodb.smd.monitor.hub.GroupStatistics;
import app.dodb.smd.monitor.hub.HandlingStatistics;
import app.dodb.smd.monitor.hub.LinkStatistics;
import app.dodb.smd.monitor.hub.OtherStatistics;
import app.dodb.smd.monitor.hub.RaisedFailureStatistics;
import app.dodb.smd.monitor.hub.SinkRouteStatistics;
import app.dodb.smd.monitor.hub.TypeStatistics;
import app.dodb.smd.monitor.hub.span.MessageKind;
import app.dodb.smd.monitor.hub.span.Stage;
import app.dodb.smd.monitor.json.FrameView.BriefView;
import app.dodb.smd.monitor.json.FrameView.InstanceView;
import app.dodb.smd.monitor.json.FrameView.MessageTypeView;
import app.dodb.smd.monitor.json.FrameView.StatisticsView;

import java.util.Arrays;
import java.util.List;

import static app.dodb.smd.monitor.json.FrameView.p95Millis;

// The statistics of an application in the frame. Its crossings are the links into it from a parent type of another application; its links
// within, routes, sink routes, and group links are only the flow view's and the live lanes', so the flow query answers them (FlowView). The
// failed received
// counts every failed invocation the hub received since it started; the event delay is the p95 over every handling of the window, or null
// when none ran asynchronously. Its failures count every failed invocation under its own stage and exception type, those of the history
// listed; other sums what the application counted beyond its caps. The seconds of the window from provisional-from on may still grow, as the
// application's spans arrive that late.
public record ApplicationView(String name,
                              long failedReceived,
                              List<InstanceView> instances,
                              List<TypeView> types,
                              List<GroupView> groups,
                              List<CrossingView> crossings,
                              BriefView dispatches,
                              BriefView handlings,
                              Double eventDelayP95Millis,
                              List<KindView> kinds,
                              List<Long> enteredPerSecond,
                              List<Long> failedPerSecond,
                              List<FailureView> failures,
                              OtherView other,
                              long provisionalFromEpochSecond) {

    public static ApplicationView from(ApplicationStatistics statistics) {
        var kinds = Arrays.stream(MessageKind.values())
            .map(kind -> new KindView(kind.name(), BriefView.from(statistics.kinds().get(kind)), statistics.startedPerSecond().get(kind)))
            .toList();
        return new ApplicationView(statistics.application().value(), statistics.failedReceived(),
            statistics.instances().stream().map(InstanceView::from).toList(), statistics.types().stream().map(TypeView::from).toList(),
            statistics.groups().stream().map(GroupView::from).toList(), statistics.crossings().stream().map(CrossingView::from).toList(),
            BriefView.from(statistics.dispatches()),
            BriefView.from(statistics.handlings()), p95Millis(statistics.eventDelay()), kinds, statistics.enteredPerSecond(), statistics.failedPerSecond(),
            statistics.failures().stream().map(FailureView::from).toList(), OtherView.from(statistics.other()),
            statistics.provisionalFrom().getEpochSecond());
    }

    // Its history carries the counts and the p95; the type's query answers every figure of it.
    public record TypeView(String kind, String name, boolean entryPoint, StatisticsView window, BriefView history, int retained,
                           List<Long> startedPerSecond, List<Long> failedPerSecond) {

        static TypeView from(TypeStatistics type) {
            return new TypeView(type.type().kind().name(), type.type().name(), type.entryPoint(), StatisticsView.from(type.window()),
                BriefView.from(type.history()), type.retained(), type.startedPerSecond(), type.failedPerSecond());
        }
    }

    public record GroupView(String processingGroup, String delivery, BriefView window, List<HandlingView> handlings) {

        static GroupView from(GroupStatistics group) {
            return new GroupView(group.processingGroup(), group.delivery().name(), BriefView.from(group.window()), group.handlings().stream().map(HandlingView::from).toList());
        }
    }

    // The event delay is the p95 over the window, or null when the group handled none of these events asynchronously in it.
    public record HandlingView(String name, BriefView window, BriefView history, boolean asynchronous, Double delayP95Millis) {

        static HandlingView from(HandlingStatistics handling) {
            return new HandlingView(handling.type().name(), BriefView.from(handling.window()), BriefView.from(handling.history()),
                handling.asynchronous(), p95Millis(handling.delay()));
        }
    }

    public record LinkView(MessageTypeView parent, MessageTypeView child, long history, long lastSecond) {

        static LinkView from(LinkStatistics link) {
            return new LinkView(MessageTypeView.from(link.link().parent()), MessageTypeView.from(link.link().child()), link.history(), link.lastSecond());
        }
    }

    public record CrossingView(String parentApplication, LinkView link) {

        static CrossingView from(CrossingStatistics crossing) {
            return new CrossingView(crossing.parentApplication().value(), LinkView.from(crossing.link()));
        }
    }

    public record RouteView(String publisher, MessageTypeView event, String processingGroup, long history, long lastSecond) {

        static RouteView from(EventRouteStatistics route) {
            return new RouteView(route.route().publisher().value(), MessageTypeView.from(route.route().event()), route.route().processingGroup(), route.history(),
                route.lastSecond());
        }
    }

    public record SinkRouteView(MessageTypeView event, String delivery, long history, long lastSecond) {

        static SinkRouteView from(SinkRouteStatistics route) {
            return new SinkRouteView(MessageTypeView.from(route.route().event()), route.route().delivery().name(), route.history(), route.lastSecond());
        }
    }

    public record GroupLinkView(String processingGroup, MessageTypeView child, long history, long lastSecond) {

        static GroupLinkView from(GroupLinkStatistics link) {
            return new GroupLinkView(link.link().processingGroup(), MessageTypeView.from(link.link().child()), link.history(), link.lastSecond());
        }
    }

    public record KindView(String kind, BriefView window, List<Long> startedPerSecond) {
    }

    // The processing group is null for a dispatch, and only an event has handlings. A failure idle in the window has no seconds.
    public record FailureView(String kind, String type, boolean handling, String processingGroup, String failureType, long window, long history,
                              List<Long> windowPerSecond) {

        static FailureView from(RaisedFailureStatistics statistics) {
            var stage = statistics.failure().stage();
            var type = stage.type();
            return new FailureView(type.kind().name(), type.name(), stage instanceof Stage.Handling,
                stage instanceof Stage.Handling handling ? handling.processingGroup() : null, statistics.failure().failureType(),
                statistics.window(), statistics.history(), statistics.windowPerSecond());
        }
    }

    // What the application counted beyond its caps: the dispatches and handlings of the types and processing groups it did not keep apart, and
    // the edges and failures beyond theirs over the history.
    public record OtherView(BriefView dispatchWindow, BriefView dispatchHistory, BriefView handlingWindow, BriefView handlingHistory, long edgeHistory,
                            long failureHistory) {

        static OtherView from(OtherStatistics other) {
            return new OtherView(BriefView.from(other.dispatchWindow()), BriefView.from(other.dispatchHistory()), BriefView.from(other.handlingWindow()),
                BriefView.from(other.handlingHistory()), other.edgeHistory(), other.failureHistory());
        }
    }
}
