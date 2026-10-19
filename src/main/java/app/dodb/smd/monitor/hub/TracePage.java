package app.dodb.smd.monitor.hub;

import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

// One page of traces at a sequence number: the traces in scope and those matching every filter; the next page follows the cursor, when there
// is one.
public record TracePage(long sequence, List<Trace> traces, int total, int matching, Optional<TraceCursor> next) {

    public TracePage {
        requireNonNull(traces);
        requireNonNull(next);
    }
}
