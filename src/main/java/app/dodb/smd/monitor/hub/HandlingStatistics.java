package app.dodb.smd.monitor.hub;

import app.dodb.smd.monitor.hub.span.MessageType;

import static java.util.Objects.requireNonNull;

// The event delay is how long after its publication returned the processing group started handling the event, over the window. A
// handling is asynchronous once the group started handling one of its events after the publication returned.
public record HandlingStatistics(MessageType type, Counts window, Counts history, LatencyHistogram delay, boolean asynchronous) {

    public HandlingStatistics {
        requireNonNull(type);
        requireNonNull(window);
        requireNonNull(history);
        requireNonNull(delay);
    }
}
