package app.dodb.smd.monitor.hub;

import app.dodb.smd.monitor.hub.catalog.SinkRoute;

import static java.util.Objects.requireNonNull;

public record SinkRouteStatistics(SinkRoute route, long history, long lastSecond) {

    public SinkRouteStatistics {
        requireNonNull(route);
    }
}
