package app.dodb.smd.monitor.json;

import app.dodb.smd.monitor.hub.RaisedFailureStatistics;
import app.dodb.smd.monitor.hub.span.ApplicationName;
import app.dodb.smd.monitor.hub.span.Stage;

import java.util.List;

import static java.util.Objects.requireNonNull;

// The failure causes a view shows, in its order, out of the total that match: each a stage failing with one exception type, its failures counted
// over the window and the history, and second by second over the window. The frame leaves the causes out, as a page shows only the first few.
public record CausesView(String hubSession, long sequence, int total, List<CauseView> causes) {

    public CausesView {
        requireNonNull(hubSession);
        requireNonNull(causes);
    }

    // The processing group is null for a dispatch, and only an event has handlings. A cause idle in the window has no seconds.
    public record CauseView(String application, String kind, String type, boolean handling, String processingGroup, String failureType, long window,
                            long history, List<Long> windowPerSecond) {

        public static CauseView from(ApplicationName application, RaisedFailureStatistics statistics) {
            var stage = statistics.failure().stage();
            var type = stage.type();
            return new CauseView(application.value(), type.kind().name(), type.name(), stage instanceof Stage.Handling,
                stage instanceof Stage.Handling handling ? handling.processingGroup() : null, statistics.failure().failureType(), statistics.window(),
                statistics.history(), statistics.windowPerSecond());
        }
    }
}
