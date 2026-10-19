package app.dodb.smd.monitor.hub.intake;

import app.dodb.smd.monitor.hub.span.InstanceName;
import app.dodb.smd.monitor.hub.span.SessionId;

import java.time.Instant;

import static java.util.Objects.requireNonNull;

// An instance admission lets report, with the session of its latest batch; it is silent once it sent nothing for silent-after, and its clock
// runs ahead while admission refused a span of it for starting too far ahead of the hub's clock within the last minute.
public record ReportingInstance(InstanceName name, SessionId session, Instant lastReportedAt, boolean silent, boolean clockAhead) {

    public ReportingInstance {
        requireNonNull(name);
        requireNonNull(session);
        requireNonNull(lastReportedAt);
    }
}
