package app.dodb.smd.monitor.hub;

import app.dodb.smd.monitor.hub.catalog.EventRoute;

import static java.util.Objects.requireNonNull;

public record EventRouteStatistics(EventRoute route, long history, long lastSecond) {

    public EventRouteStatistics {
        requireNonNull(route);
    }
}
