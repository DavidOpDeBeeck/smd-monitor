package app.dodb.smd.monitor.hub;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

// One page of the tape at a sequence number: the invocations in scope, those matching every filter, and the slowest of them; the next page
// follows the cursor, when there is one.
public record TapePage(long sequence, List<RetainedInvocation> invocations, int total, int matching, Optional<Duration> slowest,
                       Optional<InvocationId> next) {

    public TapePage {
        requireNonNull(invocations);
        requireNonNull(slowest);
        requireNonNull(next);
    }
}
