package app.dodb.smd.monitor.json;

import app.dodb.smd.monitor.hub.ApplicationStatistics;
import app.dodb.smd.monitor.hub.RaisedFailureStatistics;
import app.dodb.smd.monitor.hub.span.Stage;

import java.util.List;

import static java.util.Objects.requireNonNull;

// The failure causes a view shows, in its order, out of the total that match: each a stage failing with one exception type, its failures counted
// over the window and the history, and second by second over the window up to the end second, that of the epoch it answers from. The frame leaves
// the causes out, as a page shows only the first few.
public record CausesView(String hubSession, long sequence, long endEpochSecond, int total, List<CauseView> causes) {

    public CausesView {
        requireNonNull(hubSession);
        requireNonNull(causes);
    }

    // The processing group is null for a dispatch, and only an event has handlings. A cause idle in the window has no seconds. Started counts the
    // invocations of its stage over the history, its type's dispatches or its group's handlings of the event, of which the history failed.
    public record CauseView(String application, String kind, String type, boolean handling, String processingGroup, String failureType, long window,
                            long history, List<Long> windowPerSecond, long started) {

        public static CauseView from(ApplicationStatistics application, RaisedFailureStatistics statistics) {
            var stage = statistics.failure().stage();
            var type = stage.type();
            var started = stage instanceof Stage.Handling handling
                ? application.groups().stream().filter(group -> group.processingGroup().equals(handling.processingGroup()))
                    .flatMap(group -> group.handlings().stream()).filter(each -> each.type().equals(type))
                    .mapToLong(each -> each.history().started()).findFirst().orElse(0)
                : application.types().stream().filter(each -> each.type().equals(type)).mapToLong(each -> each.history().started()).findFirst().orElse(0);
            return new CauseView(application.application().value(), type.kind().name(), type.name(), stage instanceof Stage.Handling,
                stage instanceof Stage.Handling handling ? handling.processingGroup() : null, statistics.failure().failureType(), statistics.window(),
                statistics.history(), statistics.windowPerSecond(), started);
        }
    }
}
