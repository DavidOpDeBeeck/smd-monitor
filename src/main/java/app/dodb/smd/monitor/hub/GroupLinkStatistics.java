package app.dodb.smd.monitor.hub;

import app.dodb.smd.monitor.hub.catalog.GroupLink;

import static java.util.Objects.requireNonNull;

public record GroupLinkStatistics(GroupLink link, long history, long lastSecond) {

    public GroupLinkStatistics {
        requireNonNull(link);
    }
}
