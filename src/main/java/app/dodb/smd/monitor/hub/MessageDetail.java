package app.dodb.smd.monitor.hub;

import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

// A retained invocation at a sequence number with its retained ancestors from the root of its trace down to its parent, its first retained
// children of how many, and the failed child whose failure it most likely propagated.
public record MessageDetail(long sequence, RetainedInvocation invocation, InvocationId root, List<RetainedInvocation> ancestors, List<Invocation> children,
                            int childCount, Optional<Invocation> propagatedFrom) {

    public static final int MAX_CHILDREN = 200;

    public MessageDetail {
        requireNonNull(invocation);
        requireNonNull(root);
        requireNonNull(ancestors);
        requireNonNull(children);
        requireNonNull(propagatedFrom);
    }
}
