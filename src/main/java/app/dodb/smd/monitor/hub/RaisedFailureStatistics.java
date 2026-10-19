package app.dodb.smd.monitor.hub;

import app.dodb.smd.monitor.hub.catalog.RaisedFailure;

import java.util.List;

import static java.util.Objects.requireNonNull;

// How many invocations failed with the stage and exception type, over the window and the history, and in each second of the window; a failure
// idle in the window has no seconds, as most of an application's failures are.
public record RaisedFailureStatistics(RaisedFailure failure, long window, long history, List<Long> windowPerSecond) {

    public RaisedFailureStatistics {
        requireNonNull(failure);
        requireNonNull(windowPerSecond);
    }
}
