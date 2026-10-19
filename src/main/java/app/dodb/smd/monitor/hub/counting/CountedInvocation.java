package app.dodb.smd.monitor.hub.counting;

import app.dodb.smd.monitor.hub.span.MessageKind;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;

import static java.util.Objects.requireNonNull;

// An invocation as the counters count it, by the catalog's IDs: its stage, the kind it dispatched unless it is a handling, its instance, and
// the stage and exception type it failed with.
public record CountedInvocation(Instant startedAt, Duration duration, int stage, Optional<MessageKind> dispatched, int instance, boolean entry,
                                OptionalInt failure) {

    public CountedInvocation {
        requireNonNull(startedAt);
        requireNonNull(duration);
        requireNonNull(dispatched);
        requireNonNull(failure);
        if (stage < 0 || instance < 0 || failure.orElse(0) < 0) {
            throw new IllegalArgumentException("CountedInvocation requires catalog IDs of at least 0; got stage " + stage + ", instance " + instance + ", failure "
                + failure);
        }
        if (entry && dispatched.isEmpty()) {
            throw new IllegalArgumentException("CountedInvocation of stage " + stage + " is an entry point, which only a dispatch can be");
        }
    }
}
