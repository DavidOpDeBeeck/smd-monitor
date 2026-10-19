package app.dodb.smd.monitor.hub;

import app.dodb.smd.monitor.hub.span.ApplicationName;
import app.dodb.smd.monitor.hub.span.MessageKind;
import app.dodb.smd.monitor.hub.span.Outcome;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

// A retained trace: its root, its size in invocations, and its figures. The applications are in the order their members started; the
// composition counts the dispatches of each kind besides the root.
public record Trace(Invocation root, int size, int handlings, int failures, Instant end, int threads, List<ApplicationName> applications,
                    Map<MessageKind, Integer> composition) {

    public Trace {
        requireNonNull(root);
        requireNonNull(end);
        requireNonNull(applications);
        requireNonNull(composition);
        if (size < 1 || handlings < 0 || handlings > size || failures < 0 || failures > size) {
            throw new IllegalArgumentException("Trace of invocation " + root.id().value() + " requires at least its root, and handlings and failures among "
                + "its members; got " + size + " members, " + handlings + " handlings, and " + failures + " failures");
        }
    }

    public Instant start() {
        return root.startedAt();
    }

    public Duration duration() {
        return Duration.between(root.startedAt(), end);
    }

    // The dispatched messages, leaving out the handlings.
    public int messages() {
        return size - handlings;
    }

    public Outcome.Status status() {
        return failures > 0 ? Outcome.Status.FAILED : Outcome.Status.COMPLETED;
    }

    public boolean touches(Optional<ApplicationName> scope) {
        return scope.isEmpty() || applications.contains(scope.get());
    }
}
