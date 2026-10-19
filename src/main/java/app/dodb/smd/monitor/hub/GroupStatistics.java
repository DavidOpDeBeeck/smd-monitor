package app.dodb.smd.monitor.hub;

import app.dodb.smd.api.event.delivery.EventDelivery;

import java.util.List;

import static java.util.Objects.requireNonNull;

public record GroupStatistics(String processingGroup, EventDelivery delivery, Counts window, List<HandlingStatistics> handlings) {

    public GroupStatistics {
        requireNonNull(processingGroup);
        requireNonNull(delivery);
        requireNonNull(window);
        requireNonNull(handlings);
    }
}
