package app.dodb.smd.monitor.hub;

import static java.util.Objects.requireNonNull;

// Where a page of traces ended: the value its order sorts by and the root, which breaks ties.
public record TraceCursor(long value, InvocationId root) {

    public TraceCursor {
        requireNonNull(root);
    }
}
