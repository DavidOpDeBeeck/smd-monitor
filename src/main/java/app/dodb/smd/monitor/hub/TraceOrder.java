package app.dodb.smd.monitor.hub;

import java.util.Comparator;

import static java.util.Comparator.comparingLong;

// Largest value first; equal values put the newest root first, so every trace has one place in the order.
public enum TraceOrder {
    NEWEST,
    SLOWEST,
    LARGEST;

    TraceCursor cursorOf(Trace trace) {
        var value = switch (this) {
            case NEWEST -> trace.root().id().value();
            case SLOWEST -> trace.duration().toNanos();
            case LARGEST -> trace.messages();
        };
        return new TraceCursor(value, trace.root().id());
    }

    Comparator<TraceCursor> comparator() {
        return comparingLong(TraceCursor::value).thenComparingLong(cursor -> cursor.root().value()).reversed();
    }
}
