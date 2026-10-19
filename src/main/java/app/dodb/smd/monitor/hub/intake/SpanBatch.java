package app.dodb.smd.monitor.hub.intake;

import app.dodb.smd.monitor.hub.span.ApplicationName;
import app.dodb.smd.monitor.hub.span.InstanceName;
import app.dodb.smd.monitor.hub.span.MessageSpan;
import app.dodb.smd.monitor.hub.span.SessionId;
import app.dodb.smd.monitor.hub.span.SinkSend;

import java.util.List;

import static java.util.Objects.requireNonNull;

// The invocations one instance exported in one resource, and the publications it sent to its sinks. An empty batch only reports its instance.
public record SpanBatch(ApplicationName application, InstanceName instance, SessionId session, List<MessageSpan> spans, List<SinkSend> sends) {

    public SpanBatch {
        requireNonNull(application);
        requireNonNull(instance);
        requireNonNull(session);
        requireNonNull(spans);
        requireNonNull(sends);
    }

    public int size() {
        return spans.size() + sends.size();
    }
}
