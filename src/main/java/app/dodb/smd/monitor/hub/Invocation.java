package app.dodb.smd.monitor.hub;

import app.dodb.smd.monitor.hub.span.ApplicationName;
import app.dodb.smd.monitor.hub.span.InstanceName;
import app.dodb.smd.monitor.hub.span.MessageSpan;
import app.dodb.smd.monitor.hub.span.MessageType;
import app.dodb.smd.monitor.hub.span.Outcome;
import app.dodb.smd.monitor.hub.span.Stage;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

public record Invocation(InvocationId id, ApplicationName application, InstanceName instance, MessageSpan span) {

    public Invocation {
        requireNonNull(id);
        requireNonNull(application);
        requireNonNull(instance);
        requireNonNull(span);
    }

    public MessageType type() {
        return span.stage().type();
    }

    public ApplicationType applicationType() {
        return new ApplicationType(application, type());
    }

    public Instant startedAt() {
        return span.startedAt();
    }

    public Instant completedAt() {
        return span.completedAt();
    }

    public Duration duration() {
        return span.outcome().duration();
    }

    public boolean handling() {
        return span.stage() instanceof Stage.Handling;
    }

    public Optional<String> processingGroup() {
        return span.stage() instanceof Stage.Handling handling ? handling.processingGroup() : Optional.empty();
    }

    public boolean failed() {
        return span.outcome().status() == Outcome.Status.FAILED;
    }

    public Optional<String> failureType() {
        return span.outcome() instanceof Outcome.Failed(var ignored, var failureType) ? Optional.of(failureType) : Optional.empty();
    }
}
