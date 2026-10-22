package app.dodb.smd.monitor.hub.javalin;

import app.dodb.smd.monitor.hub.MonitorHub;
import io.avaje.config.Config;

import java.util.Arrays;

import static java.util.Objects.requireNonNull;

public final class MonitorHubServer implements AutoCloseable {

    private final MonitorHub hub;
    private final UiServer ui;
    private final OtlpServer otlp;

    private MonitorHubServer(MonitorHub hub, UiServer ui, OtlpServer otlp) {
        this.hub = requireNonNull(hub);
        this.ui = requireNonNull(ui);
        this.otlp = requireNonNull(otlp);
    }

    public static void main(String[] args) {
        if (args.length > 0) {
            throw new IllegalArgumentException("The hub takes no arguments; set its properties in an application.yaml, as environment variables, or as system "
                + "properties; got " + Arrays.toString(args));
        }
        var server = start(HubSettings.from(Config.asConfiguration()));
        Runtime.getRuntime().addShutdownHook(new Thread(server::close, "smd-monitor-hub-shutdown"));
    }

    public static MonitorHubServer start(HubSettings settings) {
        var hub = new MonitorHub(settings.hub());
        var server = new MonitorHubServer(hub, new UiServer(settings, hub), new OtlpServer(settings, hub));
        try {
            server.start();
            return server;
        } catch (RuntimeException failure) {
            server.close();
            throw failure;
        }
    }

    public void start() {
        hub.start();
        ui.start();
        otlp.start();
    }

    @Override
    public void close() {
        ui.close();
        otlp.close();
        hub.close();
    }

    MonitorHub hub() {
        return hub;
    }

    public UiServer uiServer() {
        return ui;
    }

    public OtlpServer otlpServer() {
        return otlp;
    }
}
