package app.dodb.smd.monitor.hub.intake;

import app.dodb.smd.monitor.hub.span.ApplicationName;

import java.util.Map;

import static java.util.Objects.requireNonNull;

// What the intake queues hold now, the spans and sends the writer drained of them since the hub started, and those of each application they could
// not take since then, as its queue was full.
public record QueueHealth(long queued, long drained, Map<ApplicationName, Long> full) {

    public QueueHealth {
        requireNonNull(full);
    }
}
