package app.dodb.smd.monitor.json;

import app.dodb.smd.monitor.hub.Invocation;

import java.util.List;
import java.util.UUID;

// Newest first.
public record FoundView(String hubSession, List<InvocationView> invocations) {

    public static FoundView from(UUID hubSession, List<Invocation> invocations) {
        return new FoundView(hubSession.toString(), invocations.stream().map(InvocationView::from).toList());
    }
}
