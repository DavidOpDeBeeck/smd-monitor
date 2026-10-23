package app.dodb.smd.monitor.json;

import app.dodb.smd.monitor.hub.ApplicationStatistics;
import app.dodb.smd.monitor.json.ApplicationView.GroupLinkView;
import app.dodb.smd.monitor.json.ApplicationView.LinkView;
import app.dodb.smd.monitor.json.ApplicationView.RouteView;
import app.dodb.smd.monitor.json.ApplicationView.SinkRouteView;
import app.dodb.smd.monitor.json.FrameView.BriefView;
import app.dodb.smd.monitor.json.FrameView.MessageTypeView;

import java.util.List;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

// How messages flow within and between the applications asked about, which the frame leaves out as only the flow view and the live lanes draw
// it: each application's links between its types, the routes of events to its processing groups, its events sent to its sinks, and the messages
// its groups dispatched. The crossings between applications stay in the frame.
public record FlowView(String hubSession, long sequence, List<FlowApplicationView> applications) {

    public FlowView {
        requireNonNull(hubSession);
        requireNonNull(applications);
    }

    public static FlowView from(UUID hubSession, long sequence, List<ApplicationStatistics> applications) {
        return new FlowView(hubSession.toString(), sequence, applications.stream().map(FlowApplicationView::from).toList());
    }

    // The types carry what a card shows of each; the failing types are those that failed in the window, with their failures second by second, so a
    // card rings as its type fails.
    public record FlowApplicationView(String name, List<LinkView> links, List<RouteView> routes, List<SinkRouteView> sinkRoutes, List<GroupLinkView> groupLinks,
                                      List<FlowTypeView> types, List<FailingTypeView> failingTypes) {

        public FlowApplicationView {
            requireNonNull(name);
            requireNonNull(links);
            requireNonNull(routes);
            requireNonNull(sinkRoutes);
            requireNonNull(groupLinks);
            requireNonNull(types);
            requireNonNull(failingTypes);
        }

        static FlowApplicationView from(ApplicationStatistics statistics) {
            return new FlowApplicationView(statistics.application().value(), statistics.links().stream().map(LinkView::from).toList(),
                statistics.routes().stream().map(RouteView::from).toList(), statistics.sinkRoutes().stream().map(SinkRouteView::from).toList(),
                statistics.groupLinks().stream().map(GroupLinkView::from).toList(),
                statistics.types().stream().map(type -> new FlowTypeView(MessageTypeView.from(type.type()), type.window().started(), BriefView.from(type.history())))
                    .toList(),
                statistics.types().stream().filter(type -> type.window().failed() > 0)
                    .map(type -> new FailingTypeView(MessageTypeView.from(type.type()), type.failedPerSecond())).toList());
        }
    }

    // A card's figures: the dispatches of the window, and the counts and p95 of the history.
    public record FlowTypeView(MessageTypeView type, long windowStarted, BriefView history) {

        public FlowTypeView {
            requireNonNull(type);
            requireNonNull(history);
        }
    }

    public record FailingTypeView(MessageTypeView type, List<Long> failedPerSecond) {

        public FailingTypeView {
            requireNonNull(type);
            requireNonNull(failedPerSecond);
        }
    }
}
