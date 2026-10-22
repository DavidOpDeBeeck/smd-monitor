package app.dodb.smd.monitor.json;

import app.dodb.smd.monitor.hub.TypeStatistics;
import app.dodb.smd.monitor.hub.span.ApplicationName;
import app.dodb.smd.monitor.json.FrameView.BriefView;
import app.dodb.smd.monitor.json.FrameView.StatisticsView;

import java.util.List;

import static java.util.Objects.requireNonNull;

// The figures of the message types a view shows, in its order, out of the total selected: each type's counts and latency over the window, its
// counts over the history, its dispatches the hub still retains, and its dispatches second by second over the window.
public record TypeFiguresView(String hubSession, long sequence, int total, List<TypeFigureView> types) {

    public TypeFiguresView {
        requireNonNull(hubSession);
        requireNonNull(types);
    }

    public record TypeFigureView(String application, String kind, String name, boolean entryPoint, StatisticsView window, BriefView history, int retained,
                                 List<Long> startedPerSecond) {

        public static TypeFigureView from(ApplicationName application, TypeStatistics type) {
            return new TypeFigureView(application.value(), type.type().kind().name(), type.type().name(), type.entryPoint(), StatisticsView.from(type.window()),
                BriefView.from(type.history()), type.retained(), type.startedPerSecond());
        }
    }
}
