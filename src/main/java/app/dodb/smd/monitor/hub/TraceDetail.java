package app.dodb.smd.monitor.hub;

import java.util.List;

import static java.util.Objects.requireNonNull;

// A trace at a sequence number with the parent of each member, in ascending sequence.
public record TraceDetail(long sequence, Trace trace, List<RetainedInvocation> members) {

    public TraceDetail {
        requireNonNull(trace);
        requireNonNull(members);
    }
}
