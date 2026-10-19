package app.dodb.smd.monitor.hub;

import app.dodb.smd.monitor.hub.catalog.CausalLink;

import static java.util.Objects.requireNonNull;

public record LinkStatistics(CausalLink link, long history, long lastSecond) {

    public LinkStatistics {
        requireNonNull(link);
    }
}
