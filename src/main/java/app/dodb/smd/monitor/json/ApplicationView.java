package app.dodb.smd.monitor.json;

import app.dodb.smd.monitor.hub.ApplicationStatistics;
import app.dodb.smd.monitor.hub.CrossingStatistics;
import app.dodb.smd.monitor.hub.EventRouteStatistics;
import app.dodb.smd.monitor.hub.GroupLinkStatistics;
import app.dodb.smd.monitor.hub.GroupStatistics;
import app.dodb.smd.monitor.hub.HandlingStatistics;
import app.dodb.smd.monitor.hub.LinkStatistics;
import app.dodb.smd.monitor.hub.OtherStatistics;
import app.dodb.smd.monitor.hub.SinkRouteStatistics;
import app.dodb.smd.monitor.hub.TypeStatistics;
import app.dodb.smd.monitor.hub.UpstreamStatistics;
import app.dodb.smd.monitor.hub.span.MessageKind;
import app.dodb.smd.monitor.json.FrameView.BriefView;
import app.dodb.smd.monitor.json.FrameView.InstanceView;
import app.dodb.smd.monitor.json.FrameView.MessageTypeView;

import java.util.Arrays;
import java.util.List;

import static app.dodb.smd.monitor.json.FrameView.p95Millis;

// The statistics of an application in the frame. Its crossings are the links into it from a parent type of another application, and its
// upstream the other applications whose messages caused its invocations, its routes from their events included; its links
// within, routes, sink routes, and group links are only the flow view's and the live lanes', so the flow query answers them (FlowView). The
// failed received
// counts every failed invocation the hub received since it started; the event delay is the p95 over every handling of the window, or null
// when none ran asynchronously. Its failure causes come with the causes query (CausesView), as a page shows only the first few; other sums
// what the application counted beyond its caps. The seconds of the window from provisional-from on may still grow, as the application's spans
// arrive that late.
public record ApplicationView(String name,
                              long failedReceived,
                              List<InstanceView> instances,
                              List<TypeView> types,
                              List<GroupView> groups,
                              List<CrossingView> crossings,
                              List<UpstreamView> upstream,
                              BriefView dispatches,
                              BriefView handlings,
                              Double eventDelayP95Millis,
                              List<KindView> kinds,
                              List<Long> enteredPerSecond,
                              List<Long> failedPerSecond,
                              OtherView other,
                              long provisionalFromEpochSecond) {

    public static ApplicationView from(ApplicationStatistics statistics) {
        var kinds = Arrays.stream(MessageKind.values())
            .map(kind -> new KindView(kind.name(), BriefView.from(statistics.kinds().get(kind)), statistics.startedPerSecond().get(kind)))
            .toList();
        return new ApplicationView(statistics.application().value(), statistics.failedReceived(),
            statistics.instances().stream().map(InstanceView::from).toList(), statistics.types().stream().map(TypeView::from).toList(),
            statistics.groups().stream().map(GroupView::from).toList(), statistics.crossings().stream().map(CrossingView::from).toList(),
            statistics.upstream().stream().map(UpstreamView::from).toList(), BriefView.from(statistics.dispatches()),
            BriefView.from(statistics.handlings()), p95Millis(statistics.eventDelay()), kinds, statistics.enteredPerSecond(), statistics.failedPerSecond(),
            OtherView.from(statistics.other()),
            statistics.provisionalFrom().getEpochSecond());
    }

    // A type the application dispatched, with its dispatches the hub still retains, which the type filters show. What it counted comes with the
    // queries of the views that show it: the type figures (TypeFiguresView), the type's (RetainedTypeView), and the flow (FlowView).
    public record TypeView(String kind, String name, boolean entryPoint, int retained) {

        static TypeView from(TypeStatistics type) {
            return new TypeView(type.type().kind().name(), type.type().name(), type.entryPoint(), type.retained());
        }
    }

    public record GroupView(String processingGroup, String delivery, BriefView window, List<HandlingView> handlings) {

        static GroupView from(GroupStatistics group) {
            return new GroupView(group.processingGroup(), group.delivery().name(), BriefView.from(group.window()), group.handlings().stream().map(HandlingView::from).toList());
        }
    }

    // The event delay is the p95 over the window, or null when the group handled none of these events asynchronously in it. What the handling
    // counted is only an expanded group's, the failures view's, and the flow view's, so the handlings query answers it (HandlingsView).
    public record HandlingView(String name, boolean asynchronous, Double delayP95Millis) {

        static HandlingView from(HandlingStatistics handling) {
            return new HandlingView(handling.type().name(), handling.asynchronous(), p95Millis(handling.delay()));
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

    public record UpstreamView(String application, long history, long lastSecond) {

        static UpstreamView from(UpstreamStatistics upstream) {
            return new UpstreamView(upstream.application().value(), upstream.history(), upstream.lastSecond());
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
