package app.dodb.smd.monitor.hub;

import app.dodb.smd.monitor.hub.span.InstanceName;
import app.dodb.smd.monitor.hub.span.SessionId;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

// The export lag is the p99 of how long recent spans of the instance took to reach the hub after they ended; its clock runs ahead while
// admission refused a span of it for starting too far ahead of the hub's clock within the last minute.
public record Instance(InstanceName name, SessionId session, Instant lastReportedAt, boolean silent, boolean clockAhead, Counts dispatches, Counts handlings,
                       Optional<Duration> exportLag) {

    public Instance {
        requireNonNull(name);
        requireNonNull(session);
        requireNonNull(lastReportedAt);
        requireNonNull(dispatches);
        requireNonNull(handlings);
        requireNonNull(exportLag);
    }
}
