package app.dodb.smd.monitor.hub;

import java.util.List;

import static java.util.Objects.requireNonNull;

// What an epoch retains, for tests to compare: its invocations, oldest first, and the parents found for them in the order they were found.
record Retained(long sequence, List<Invocation> invocations, List<ParentSummary> parents) {

    Retained {
        requireNonNull(invocations);
        requireNonNull(parents);
    }
}
