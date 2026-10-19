package app.dodb.smd.monitor.hub.join;

import app.dodb.smd.api.message.MessageId;
import app.dodb.smd.monitor.hub.InvocationId;
import app.dodb.smd.monitor.hub.span.ApplicationName;
import app.dodb.smd.monitor.hub.span.MessageType;
import app.dodb.smd.monitor.hub.span.Stage;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

// What linking an invocation to its parent and counting the link needs to know of it, which the span index keeps after the hub evicts the
// invocation, with the ID the hub numbered the invocation by.
public record Fact(InvocationId id, ApplicationName application, Stage stage, MessageId messageId, Instant startedAt, Duration duration) {

    public Fact {
        requireNonNull(id);
        requireNonNull(application);
        requireNonNull(stage);
        requireNonNull(messageId);
        requireNonNull(startedAt);
        requireNonNull(duration);
    }

    public MessageType type() {
        return stage.type();
    }

    public Optional<String> processingGroup() {
        return stage instanceof Stage.Handling handling ? handling.processingGroup() : Optional.empty();
    }

    public Instant completedAt() {
        return startedAt.plus(duration);
    }
}
