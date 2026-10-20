package app.dodb.smd.monitor.hub.spring;

import app.dodb.smd.monitor.hub.Epoch;
import app.dodb.smd.monitor.hub.MonitorHub;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;

import java.net.URI;
import java.nio.charset.StandardCharsets;

import static java.util.Objects.requireNonNull;

// The hub's WebSocket as it runs, for a saturation run: Spring Boot's Tomcat serving MonitorFrameSocket at /live on a free loopback port, over the
// run's hub, which the run starts and closes. The publisher reports every frame it writes, as SaturationRun's own publisher does.
public final class SaturationFrameServer implements AutoCloseable {

    private final ConfigurableApplicationContext context;

    private SaturationFrameServer(ConfigurableApplicationContext context) {
        this.context = requireNonNull(context);
    }

    public static SaturationFrameServer start(MonitorHub hub, FrameWritten written) {
        var context = new SpringApplicationBuilder(Served.class)
            .initializers(each -> {
                each.getBeanFactory().registerSingleton("monitorHub", hub);
                each.getBeanFactory().registerSingleton("frameWritten", written);
            })
            .properties("server.port=0", "server.address=127.0.0.1", "spring.main.banner-mode=off", "logging.level.root=warn")
            .run();
        return new SaturationFrameServer(context);
    }

    public URI live() {
        var port = ((WebServerApplicationContext) context).getWebServer().getPort();
        return URI.create("ws://127.0.0.1:" + port + "/live");
    }

    @Override
    public void close() {
        context.close();
    }

    // When the publisher started building an epoch's frame and when it had written its first message, the message's size in bytes, and the epochs
    // it skipped before it.
    public interface FrameWritten {

        void written(Epoch epoch, long startedNanos, long writtenNanos, long bytes, long skipped);
    }

    // As MonitorHubApplication assembles the socket, without the OTLP endpoint, the queries, and the page.
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EnableWebSocket
    static class Served {

        @Bean
        MonitorFrames monitorHubFrames(FrameWritten written) {
            return new TimedFrames(written);
        }

        @Bean(initMethod = "start", destroyMethod = "close")
        MonitorFrameSocket monitorHubSocket(MonitorHub hub, MonitorFrames frames) {
            return new MonitorFrameSocket(hub, frames);
        }

        @Bean
        WebSocketConfigurer monitorHubWebSocketConfigurer(MonitorFrameSocket socket) {
            return registry -> registry.addHandler(socket, "/live");
        }
    }

    // Writes the delta, or the first snapshot, on the publisher as it publishes the frame, where the socket would write it for the first client in
    // sync right after; the socket then sends the message written here. Only when no client is in sync does this write a message the socket would not.
    private static final class TimedFrames extends MonitorFrames {

        private static final long NONE = -1;
        private final FrameWritten written;

        private TimedFrames(FrameWritten written) {
            this.written = requireNonNull(written);
        }

        @Override
        PublishedFrame publish(Epoch epoch, PublishedFrame previous) {
            var started = System.nanoTime();
            var frame = super.publish(epoch, previous);
            var message = frame.messageFor(previous == null ? NONE : previous.epoch());
            var writtenAt = System.nanoTime();
            written.written(epoch, started, writtenAt, message.getBytes(StandardCharsets.UTF_8).length, previous == null ? 0 : epoch.sequence() - previous.epoch() - 1);
            return frame;
        }
    }
}
