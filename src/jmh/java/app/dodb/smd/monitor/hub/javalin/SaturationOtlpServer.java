package app.dodb.smd.monitor.hub.javalin;

import app.dodb.smd.monitor.hub.HubConfig;
import app.dodb.smd.monitor.hub.MonitorHub;
import app.dodb.smd.monitor.hub.otlp.OtlpAdmission;

import java.net.URI;
import java.util.List;

import static java.util.Objects.requireNonNull;

// The hub's OTLP server as it runs, for a saturation run that exports over OTLP/HTTP: on a free loopback port, over the run's hub, which the run
// starts and closes.
public final class SaturationOtlpServer implements AutoCloseable {

    private final OtlpServer server;

    private SaturationOtlpServer(OtlpServer server) {
        this.server = requireNonNull(server);
    }

    public static SaturationOtlpServer start(MonitorHub hub) {
        var settings = new HubSettings(0, "127.0.0.1", 0, "127.0.0.1", List.of(), HubConfig.withDefaults().build());
        var server = new OtlpServer(settings, hub);
        server.start();
        return new SaturationOtlpServer(server);
    }

    public URI traces() {
        return URI.create("http://127.0.0.1:" + server.port() + OtlpAdmission.PATH);
    }

    @Override
    public void close() {
        server.close();
    }
}
