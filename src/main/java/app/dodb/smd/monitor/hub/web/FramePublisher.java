package app.dodb.smd.monitor.hub.web;

import app.dodb.smd.monitor.hub.MonitorHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static java.util.Objects.requireNonNull;

// Sends every client the frame of each epoch the hub publishes (W1 to W3): the changes since the frame it received last, or the whole snapshot
// to a client that connects or missed a frame. The publisher thread compares each epoch's snapshot with the one it published before; the
// messages are written at most once per epoch and shared. Every client receives the statistics of the whole system; the page applies its scope.
public final class FramePublisher implements AutoCloseable {

    static final int MAX_CLIENTS = 64;
    private static final Logger LOGGER = LoggerFactory.getLogger(FramePublisher.class);
    // Before a client received a frame.
    private static final long NONE = -1;
    private final MonitorHub hub;
    private final MonitorFrames frames;
    private final int maxClients;
    private final Map<Sink, Client> clients = new ConcurrentHashMap<>();
    private final Thread publisher = Thread.ofPlatform().daemon().name("smd-monitor-frames").unstarted(this::publishEpochs);
    private volatile PublishedFrame latest;
    private boolean closed;

    public FramePublisher(MonitorHub hub) {
        this(hub, new MonitorFrames());
    }

    public FramePublisher(MonitorHub hub, MonitorFrames frames) {
        this(hub, frames, MAX_CLIENTS);
    }

    // A benchmark tunes how many clients it takes.
    public FramePublisher(MonitorHub hub, MonitorFrames frames, int maxClients) {
        this.hub = requireNonNull(hub);
        this.frames = requireNonNull(frames);
        this.maxClients = maxClients;
    }

    public void start() {
        publisher.start();
    }

    // Takes a client to send the frames to, from the latest one on, and reports whether it did: not once as many are connected as the publisher
    // takes, or once it closed.
    public synchronized boolean connect(Sink sink) {
        if (closed || clients.size() >= maxClients) {
            return false;
        }
        var client = new Client(sink);
        clients.put(sink, client);
        var frame = latest;
        if (frame != null) {
            publish(List.of(client), frame);
        }
        return true;
    }

    // The client went away; it receives no further messages.
    public void disconnect(Sink sink) {
        clients.remove(sink);
    }

    private void publishEpochs() {
        var sequence = 0L;
        PublishedFrame previous = null;
        try {
            while (!Thread.currentThread().isInterrupted()) {
                // A frame that takes longer than a second skips to the latest epoch; its changes are those since the frame published before.
                var epoch = hub.awaitEpochAfter(sequence);
                sequence = epoch.sequence();
                try {
                    var frame = frames.publish(epoch, previous);
                    previous = frame;
                    latest = frame;
                    publish(clients.values(), frame);
                } catch (RuntimeException failure) {
                    LOGGER.warn("Unable to publish SMD monitor frames", failure);
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void publish(Collection<Client> recipients, PublishedFrame frame) {
        for (var client : recipients) {
            // One send per client at a time; a busy client skips the frame, and receives the changes since the one it was sent with the next, or
            // the snapshot once it fell further behind than the frame keeps changes for.
            if (!client.sending.compareAndSet(false, true)) {
                continue;
            }
            try {
                var message = frame.messageFor(client.received);
                client.received = frame.epoch();
                client.sink.send(message, sent -> {
                    client.sending.set(false);
                    if (!sent) {
                        client.close();
                    }
                });
            } catch (RuntimeException failure) {
                client.sending.set(false);
                client.close();
            }
        }
    }

    // Stops the publisher thread and closes every client.
    @Override
    public void close() {
        synchronized (this) {
            closed = true;
        }
        publisher.interrupt();
        try {
            publisher.join();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        clients.values().forEach(Client::close);
    }

    // Where a client receives its messages, such as a WebSocket session; it identifies the client to the publisher.
    public interface Sink {

        // Sends the message, UTF-8 JSON text that other clients share, without waiting for it to be written, then reports whether it was.
        void send(byte[] message, Consumer<Boolean> sent);

        void close();
    }

    private final class Client {
        private final Sink sink;
        private final AtomicBoolean sending = new AtomicBoolean();
        // The epoch of the frame it was last sent; only the thread that holds sending writes it.
        private volatile long received = NONE;

        private Client(Sink sink) {
            this.sink = requireNonNull(sink);
        }

        private void close() {
            clients.remove(sink);
            sink.close();
        }
    }
}
