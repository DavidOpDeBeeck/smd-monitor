package app.dodb.smd.monitor.json;

import app.dodb.smd.monitor.hub.Counts;
import app.dodb.smd.monitor.hub.RetainedType;
import app.dodb.smd.monitor.json.FrameView.StatisticsView;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

// The failure types most frequent first, and the latest dispatches newest first. The history holds every figure of the type over the history,
// of which the frame carries only the counts and the p95; it is null when the application did not report the type.
public record RetainedTypeView(String hubSession, long sequence, StatisticsView history, int retained, List<FailureTypeView> failureTypes,
                               List<InvocationView> latest) {

    public static RetainedTypeView from(UUID hubSession, RetainedType type, Optional<Counts> history) {
        return new RetainedTypeView(hubSession.toString(), type.sequence(), history.map(StatisticsView::from).orElse(null), type.retained(),
            type.failureTypes().stream().map(count -> new FailureTypeView(count.failureType(), count.count())).toList(),
            type.latest().stream().map(InvocationView::from).toList());
    }

    public record FailureTypeView(String failureType, int count) {
    }
}
