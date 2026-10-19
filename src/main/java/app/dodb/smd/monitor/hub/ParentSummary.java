package app.dodb.smd.monitor.hub;

import app.dodb.smd.api.message.MessageId;
import app.dodb.smd.monitor.hub.span.ApplicationName;
import app.dodb.smd.monitor.hub.span.MessageType;
import app.dodb.smd.monitor.hub.span.Stage;

import java.util.Optional;

import static java.util.Objects.requireNonNull;

// What a retained invocation keeps of the invocation it continues, the one its span links to: for a handling the dispatch of its message, for a
// dispatch the handling that sent it, or, when delivered, a dispatch of the same message in another application (J4). It holds no reference
// to the parent, which the hub may evict while the child stays; the sequence numbers the finding among everything the hub retains.
public record ParentSummary(long sequence, InvocationId child, InvocationId parent, ApplicationName application, MessageId messageId, Stage stage,
                            boolean delivered) {

    public ParentSummary {
        requireNonNull(child);
        requireNonNull(parent);
        requireNonNull(application);
        requireNonNull(messageId);
        requireNonNull(stage);
    }

    public MessageType type() {
        return stage.type();
    }

    public boolean handling() {
        return stage instanceof Stage.Handling;
    }

    public Optional<String> processingGroup() {
        return stage instanceof Stage.Handling handling ? handling.processingGroup() : Optional.empty();
    }
}
