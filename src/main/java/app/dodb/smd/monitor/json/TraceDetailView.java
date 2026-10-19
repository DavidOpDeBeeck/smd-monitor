package app.dodb.smd.monitor.json;

import app.dodb.smd.monitor.hub.TraceDetail;
import app.dodb.smd.monitor.json.TracePageView.TraceView;

import java.util.List;
import java.util.UUID;

// The members in ascending sequence, each with its parent.
public record TraceDetailView(String hubSession, long sequence, TraceView trace, List<RetainedInvocationView> members) {

    public static TraceDetailView from(UUID hubSession, TraceDetail detail) {
        return new TraceDetailView(hubSession.toString(), detail.sequence(), TraceView.from(detail.trace()),
            detail.members().stream().map(RetainedInvocationView::from).toList());
    }
}
