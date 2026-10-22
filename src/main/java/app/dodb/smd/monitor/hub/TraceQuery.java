package app.dodb.smd.monitor.hub;

import java.util.Optional;

import static java.util.Objects.requireNonNull;

// The traces that touch the application in the filter's scope, or every trace, in the given order after the cursor when given. A trace matches
// the filter's types, applications, and search when any of its members does; no statuses or applications select every trace, and chains are
// traces of at least two messages.
public record TraceQuery(InvocationFilter filter, boolean chains, TraceOrder order, Optional<TraceCursor> after, int limit) {

    public static final int MAX_LIMIT = 1_000;

    public TraceQuery {
        requireNonNull(filter);
        requireNonNull(order);
        requireNonNull(after);
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("TraceQuery limit must be between 1 and " + MAX_LIMIT + "; got " + limit);
        }
    }
}
