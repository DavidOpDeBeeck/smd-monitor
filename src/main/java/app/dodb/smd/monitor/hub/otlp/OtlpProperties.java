package app.dodb.smd.monitor.hub.otlp;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.InetAddress;
import java.util.Optional;

// The address is null when unset, as Spring Boot binds it, and then the port listens on every address, as server.address does.
@ConfigurationProperties(prefix = "smd.monitor.hub.otlp")
public record OtlpProperties(int port, InetAddress address) {

    public OtlpProperties {
        if (port < 0 || port > 65_535) {
            throw new IllegalArgumentException("smd.monitor.hub.otlp.port must be between 0 and 65535; got " + port);
        }
    }

    public Optional<InetAddress> listenAddress() {
        return Optional.ofNullable(address);
    }
}
