package app.dodb.smd.monitor.hub;

import java.time.Duration;

import static java.util.Objects.requireNonNull;

// The dispatches that ran during the span up to now: those the filter keeps that took at least the minimum duration, at most the limit, the
// latest started first.
public record TimelineQuery(InvocationFilter filter, Duration minDuration, Duration span, int limit) {

    public static final Duration MAX_SPAN = Duration.ofMinutes(10);
    public static final int MAX_LIMIT = 5_000;

    public TimelineQuery {
        requireNonNull(filter);
        requireNonNull(minDuration);
        requireNonNull(span);
        if (minDuration.isNegative()) {
            throw new IllegalArgumentException("TimelineQuery requires a nonnegative minimum duration; got " + minDuration);
        }
        if (!span.isPositive() || span.compareTo(MAX_SPAN) > 0) {
            throw new IllegalArgumentException("TimelineQuery span must be positive and at most " + MAX_SPAN + "; got " + span);
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("TimelineQuery limit must be between 1 and " + MAX_LIMIT + "; got " + limit);
        }
    }
}
