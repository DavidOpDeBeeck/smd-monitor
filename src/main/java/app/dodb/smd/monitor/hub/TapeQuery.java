package app.dodb.smd.monitor.hub;

import java.time.Duration;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

// Newest first: the filtered invocations that took at least the minimum duration, older than the cursor when given.
public record TapeQuery(InvocationFilter filter, Duration minDuration, Optional<InvocationId> olderThan, int limit) {

    public static final int MAX_LIMIT = 1_000;

    public TapeQuery {
        requireNonNull(filter);
        requireNonNull(minDuration);
        requireNonNull(olderThan);
        if (minDuration.isNegative()) {
            throw new IllegalArgumentException("TapeQuery requires a nonnegative minimum duration; got " + minDuration);
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("TapeQuery limit must be between 1 and " + MAX_LIMIT + "; got " + limit);
        }
    }
}
