package app.dodb.smd.monitor.hub;

import app.dodb.smd.monitor.hub.span.ApplicationName;

import static java.util.Objects.requireNonNull;

// Messages of the parent type in another application that caused messages of the child type in this one.
public record CrossingStatistics(ApplicationName parentApplication, LinkStatistics link) {

    public CrossingStatistics {
        requireNonNull(parentApplication);
        requireNonNull(link);
    }
}
