package app.dodb.smd.monitor.hub.intake;

import app.dodb.smd.monitor.hub.span.ApplicationName;

import java.util.Map;

import static java.util.Objects.requireNonNull;

// The spans and sends the intake queues hold now and the room they take of the capacity, in bytes; the spans and sends the writer drained of them
// since the hub started, and those of each application they could not take since then, as its queue was full.
public record QueueHealth(long queued, long queuedBytes, long drained, Map<ApplicationName, Long> full) {

    public QueueHealth {
        requireNonNull(full);
    }
}
