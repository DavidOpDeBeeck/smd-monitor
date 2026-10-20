package app.dodb.smd.monitor.hub.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.List;

import static java.util.Objects.requireNonNull;

@ConfigurationProperties(prefix = "smd.monitor.hub")
public record MonitorHubProperties(DataSize memory,
                                   Duration window,
                                   Duration history,
                                   Duration settle,
                                   Duration linkHorizon,
                                   int maxApplications,
                                   int maxInstances,
                                   int maxTypes,
                                   int maxGroups,
                                   int maxEdges,
                                   Duration silentAfter,
                                   Duration retireAfter,
                                   List<String> allowedOrigins) {

    public MonitorHubProperties {
        requireNonNull(memory);
        requireNonNull(window);
        requireNonNull(history);
        requireNonNull(settle);
        requireNonNull(linkHorizon);
        requireNonNull(silentAfter);
        requireNonNull(retireAfter);
        requireNonNull(allowedOrigins);
    }
}
