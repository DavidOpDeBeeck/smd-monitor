package app.dodb.smd.monitor.hub;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

// The dispatches that ran during a span ending at a moment: how many ran in scope, how many the query keeps, and the latest started of those as
// marks. A mark names the root of its trace and its nearest ancestor among the marks.
public record TimelineWindow(long sequence, Instant end, int inSpan, int matching, List<Mark> marks) {

    public TimelineWindow {
        requireNonNull(end);
        requireNonNull(marks);
    }

    public record Mark(Invocation invocation, InvocationId root, Optional<InvocationId> markedAncestor) {

        public Mark {
            requireNonNull(invocation);
            requireNonNull(root);
            requireNonNull(markedAncestor);
        }
    }
}
