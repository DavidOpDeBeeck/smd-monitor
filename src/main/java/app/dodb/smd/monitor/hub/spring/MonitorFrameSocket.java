package app.dodb.smd.monitor.hub.spring;

import app.dodb.smd.monitor.hub.MonitorHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.adapter.standard.StandardWebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.util.Objects.requireNonNull;

// Sends every client the frame of each epoch the hub publishes (W1 to W3): the changes since the frame it received last, or the whole snapshot
// to a client that connects or missed a frame. The publisher thread compares each epoch's snapshot with the one it published before; the
// messages are written at most once per epoch and shared.
class MonitorFrameSocket extends TextWebSocketHandler implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(MonitorFrameSocket.class);
    private static final int MAX_CLIENTS = 64;
    private static final long SEND_TIMEOUT_MILLIS = 5_000;
    // Before a client received a frame.
    private static final long NONE = -1;
    private final MonitorHub hub;
    private final MonitorFrames frames;
    private final ConcurrentHashMap<String, Client> clients = new ConcurrentHashMap<>();
    private final Thread publisher = Thread.ofPlatform().daemon().name("smd-monitor-frames").unstarted(this::publishEpochs);
    private volatile PublishedFrame latest;
    private volatile boolean closed;

    MonitorFrameSocket(MonitorHub hub, MonitorFrames frames) {
        this.hub = requireNonNull(hub);
        this.frames = requireNonNull(frames);
    }

    void start() {
        publisher.start();
    }

    @Override
    public synchronized void afterConnectionEstablished(WebSocketSession session) throws IOException {
        if (closed || clients.size() >= MAX_CLIENTS || !(session instanceof StandardWebSocketSession standard)) {
            session.close(CloseStatus.SERVICE_OVERLOAD);
            return;
        }
        // Every client receives the statistics of the whole system; the page applies its scope, so a query naming an application is ignored.
        standard.getNativeSession().getAsyncRemote().setSendTimeout(SEND_TIMEOUT_MILLIS);
        var client = new Client(standard);
        clients.put(session.getId(), client);
        var frame = latest;
        if (frame != null) {
            publish(List.of(client), frame);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        clients.remove(session.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        disconnect(session);
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
            // One asynchronous send per client; a busy client skips the frame, and receives the snapshot of the next.
            if (!client.sending.compareAndSet(false, true)) {
                continue;
            }
            try {
                var message = frame.messageFor(client.received);
                client.received = frame.epoch();
                client.session.getNativeSession().getAsyncRemote().sendText(message, result -> {
                    if (!result.isOK()) {
                        disconnect(client.session);
                    }
                    client.sending.set(false);
                });
            } catch (RuntimeException failure) {
                client.sending.set(false);
                disconnect(client.session);
            }
        }
    }

    private void disconnect(WebSocketSession session) {
        clients.remove(session.getId());
        try {
            session.close(CloseStatus.GOING_AWAY);
        } catch (IOException ignored) {
            // The transport may already be unavailable.
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        publisher.interrupt();
        clients.values().forEach(client -> disconnect(client.session));
        clients.clear();
    }

    private static final class Client {
        private final StandardWebSocketSession session;
        private final AtomicBoolean sending = new AtomicBoolean();
        // The epoch of the frame it was last sent; only the thread that holds sending writes it.
        private volatile long received = NONE;

        private Client(StandardWebSocketSession session) {
            this.session = session;
        }
    }
}
