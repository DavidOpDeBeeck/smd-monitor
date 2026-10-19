package app.dodb.smd.monitor.hub;

import app.dodb.smd.monitor.hub.span.MessageType;

import java.util.List;

import static java.util.Objects.requireNonNull;

// The retained are the dispatches of the type the hub still retains.
public record TypeStatistics(MessageType type, boolean entryPoint, Counts window, Counts history, int retained, List<Long> startedPerSecond,
                             List<Long> failedPerSecond) {

    public TypeStatistics {
        requireNonNull(type);
        requireNonNull(window);
        requireNonNull(history);
        requireNonNull(startedPerSecond);
        requireNonNull(failedPerSecond);
    }
}
