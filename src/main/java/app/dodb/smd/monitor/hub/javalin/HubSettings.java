package app.dodb.smd.monitor.hub.javalin;

import app.dodb.smd.monitor.hub.HubConfig;
import io.avaje.config.Configuration;

import java.util.List;

import static java.util.Objects.requireNonNull;

public record HubSettings(int uiPort, String uiAddress, int otlpPort, String otlpAddress, List<String> allowedOrigins, int maxViewers, HubConfig hub) {

    private static final String HUB = "smd.monitor.hub.";

    public HubSettings {
        requireNonNull(allowedOrigins);
        requireNonNull(hub);
        if (uiPort != 0 && uiPort == otlpPort) {
            throw new IllegalArgumentException(HUB + "otlp.port must differ from " + HUB + "ui.port; both are " + uiPort);
        }
        if (maxViewers < 1) {
            throw new IllegalArgumentException(HUB + "ui.max-viewers must be at least 1, as no page could connect otherwise; got " + maxViewers);
        }
    }

    public static HubSettings from(Configuration configuration) {
        return new HubSettings(
            configuration.getInt(HUB + "ui.port"),
            configuration.getNullable(HUB + "ui.address"),
            configuration.getInt(HUB + "otlp.port"),
            configuration.getNullable(HUB + "otlp.address"),
            configuration.list().of(HUB + "ui.allowed-origins").stream()
                .map(String::strip)
                .filter(origin -> !origin.isEmpty())
                .toList(),
            configuration.getInt(HUB + "ui.max-viewers"),
            new HubConfig(configuration.getLong(HUB + "memory"),
                configuration.getDuration(HUB + "window"),
                configuration.getDuration(HUB + "history"),
                configuration.getDuration(HUB + "settle"),
                configuration.getDuration(HUB + "link-horizon"),
                configuration.getInt(HUB + "max-applications"),
                configuration.getInt(HUB + "max-instances"),
                configuration.getInt(HUB + "max-types"),
                configuration.getInt(HUB + "max-groups"),
                configuration.getInt(HUB + "max-edges"),
                configuration.getDuration(HUB + "silent-after"),
                configuration.getDuration(HUB + "retire-after")
            )
        );
    }
}
