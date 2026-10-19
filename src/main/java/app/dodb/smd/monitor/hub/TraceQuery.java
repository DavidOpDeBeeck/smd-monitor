package app.dodb.smd.monitor.hub;

import app.dodb.smd.monitor.hub.span.ApplicationName;
import app.dodb.smd.monitor.hub.span.Outcome;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;

// The traces that touch the application in scope, or every trace, in the given order after the cursor when given. A trace matches the types,
// the applications, and the search when any of its members does; no statuses or applications select every trace, and chains are traces of at
// least two messages.
public record TraceQuery(Optional<ApplicationName> scope,
                         TypeSelection types,
                         Set<ApplicationName> applications,
                         Set<Outcome.Status> statuses,
                         boolean chains,
                         String search,
                         TraceOrder order,
                         Optional<TraceCursor> after,
                         int limit) {

    public static final int MAX_LIMIT = 1_000;

    public TraceQuery {
        requireNonNull(scope);
        requireNonNull(types);
        requireNonNull(applications);
        requireNonNull(statuses);
        requireNonNull(search);
        requireNonNull(order);
        requireNonNull(after);
        search = search.strip().toLowerCase(Locale.ROOT);
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("TraceQuery limit must be between 1 and " + MAX_LIMIT + "; got " + limit);
        }
    }
}
