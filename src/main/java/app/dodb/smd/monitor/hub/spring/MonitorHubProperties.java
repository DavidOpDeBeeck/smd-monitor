package app.dodb.smd.monitor.hub.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

import static java.util.Objects.requireNonNull;

@ConfigurationProperties(prefix = "smd.monitor.hub")
public record MonitorHubProperties(@DefaultValue("256MB") DataSize memory,
                                   @DefaultValue("1m") Duration window,
                                   @DefaultValue("5m") Duration history,
                                   @DefaultValue("5s") Duration settle,
                                   @DefaultValue("60s") Duration linkHorizon,
                                   @DefaultValue("100") int maxApplications,
                                   @DefaultValue("256") int maxInstances,
                                   @DefaultValue("1000") int maxTypes,
                                   @DefaultValue("200") int maxGroups,
                                   @DefaultValue("5000") int maxEdges,
                                   @DefaultValue("10s") Duration silentAfter,
                                   @DefaultValue("10m") Duration retireAfter) {

    public MonitorHubProperties {
        requireNonNull(memory);
        requireNonNull(window);
        requireNonNull(history);
        requireNonNull(settle);
        requireNonNull(linkHorizon);
        requireNonNull(silentAfter);
        requireNonNull(retireAfter);
    }
}
