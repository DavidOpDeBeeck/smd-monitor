package app.dodb.smd.monitor.hub.javalin;

import app.dodb.smd.monitor.hub.Epoch;
import app.dodb.smd.monitor.hub.HubConfig;
import app.dodb.smd.monitor.hub.MonitorHub;
import app.dodb.smd.monitor.hub.web.FramePublisher;
import app.dodb.smd.monitor.hub.web.HubQueries;
import app.dodb.smd.monitor.hub.web.MonitorFrames;
import app.dodb.smd.monitor.hub.web.PublishedFrame;

import java.net.URI;
import java.util.List;
import java.util.OptionalInt;

import static java.util.Objects.requireNonNull;

// The hub's page server as it runs, for a saturation run with clients or pages: on a free loopback port, over the run's hub, which the run starts
// and closes. The publisher reports every frame it writes, as SaturationRun's own publisher does.
public final class SaturationFrameServer implements AutoCloseable {

    private final UiServer server;

    private SaturationFrameServer(UiServer server) {
        this.server = requireNonNull(server);
    }

    // The publisher takes every client the run connects, however many; the hub runs the given number of queries at once, or its own limit.
    public static SaturationFrameServer start(MonitorHub hub, FrameWritten written, OptionalInt queryCap) {
        var settings = new HubSettings(0, "127.0.0.1", 0, null, List.of(), HubConfig.withDefaults().build());
        var publisher = new FramePublisher(hub, new TimedFrames(written), Integer.MAX_VALUE);
        var queries = queryCap.isPresent() ? new HubQueries(hub, queryCap.getAsInt()) : new HubQueries(hub);
        var server = new UiServer(settings, hub, publisher, queries);
        server.start();
        return new SaturationFrameServer(server);
    }

    public URI api() {
        return URI.create("http://127.0.0.1:" + server.port());
    }

    public URI live() {
        return URI.create("ws://127.0.0.1:" + server.port() + UiServer.LIVE);
    }

    @Override
    public void close() {
        server.close();
    }

    // When the publisher started building an epoch's frame and when it had written its first message, the message's size in bytes, and the epochs
    // it skipped before it.
    public interface FrameWritten {

        void written(Epoch epoch, long startedNanos, long writtenNanos, long bytes, long skipped);
    }

    // Writes the delta, or the first snapshot, on the publisher as it publishes the frame, where the publisher would write it for the first client
    // in sync right after; the publisher then sends the message written here. Only when no client is in sync does this write a message the publisher
    // would not.
    private static final class TimedFrames extends MonitorFrames {

        private static final long NONE = -1;
        private final FrameWritten written;

        private TimedFrames(FrameWritten written) {
            this.written = requireNonNull(written);
        }

        @Override
        public PublishedFrame publish(Epoch epoch, PublishedFrame previous) {
            var started = System.nanoTime();
            var frame = super.publish(epoch, previous);
            var message = frame.messageFor(previous == null ? NONE : previous.epoch());
            var writtenAt = System.nanoTime();
            written.written(epoch, started, writtenAt, message.length, previous == null ? 0 : epoch.sequence() - previous.epoch() - 1);
            return frame;
        }
    }
}
