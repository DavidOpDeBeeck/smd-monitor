package app.dodb.smd.monitor.hub;

import app.dodb.smd.monitor.hub.span.ApplicationName;

import static java.util.Objects.requireNonNull;

// The invocations of an application that messages of another application caused: its dispatches whose parent ran there, and its processing
// groups' handlings of that application's events.
public record UpstreamStatistics(ApplicationName application, long history, long lastSecond) {

    public UpstreamStatistics {
        requireNonNull(application);
    }

    UpstreamStatistics plus(UpstreamStatistics other) {
        return new UpstreamStatistics(application, history + other.history, lastSecond + other.lastSecond);
    }
}
