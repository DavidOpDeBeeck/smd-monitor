package app.dodb.smd.monitor.hub;

import app.dodb.smd.monitor.hub.spring.SaturationFrameServer;
import jakarta.websocket.ClientEndpointConfig;
import jakarta.websocket.CloseReason;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.DeploymentException;
import jakarta.websocket.Endpoint;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.Extension;
import jakarta.websocket.MessageHandler;
import jakarta.websocket.Session;
import jakarta.websocket.WebSocketContainer;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import static java.util.Objects.requireNonNull;

// The pages of a saturation run that measures frame delivery: the hub's WebSocket served as it runs, and clients connected to it over the loopback
// address, offering permessage-deflate as browsers do. Each client records when it read the last part of each frame, and the frame's type and epoch
// from the head of the message; it parses nothing more, as parsing is the page's work, not the hub's. A slow client reads at most its rate of
// characters of JSON a second: it blocks the container's thread that reads its socket, so the hub's send backs up as it does behind a slow link or
// a busy page. A closed connection reconnects after half a second, as the page first does.
final class FrameClients implements AutoCloseable {

    // About what a 16 Mbit/s link carries of frames that permessage-deflate shrinks five times, as FrameBenchmark finds.
    private static final int SLOW_CHARS_PER_SECOND = 10_000_000;
    private static final String CLIENTS_PROPERTY = "saturation.clients";
    private static final String SLOW_CLIENTS_PROPERTY = "saturation.slowClients";
    private static final String SLOW_CLIENT_RATE_PROPERTY = "saturation.slowClientRate";
    private static final Duration RECONNECT = Duration.ofMillis(500);
    private static final Duration FIRST_FRAME = Duration.ofMinutes(1);
    private static final Pattern HEAD = Pattern.compile("\"type\":\"(snapshot|delta)\",\"epoch\":(\\d+)");
    private static final int HEAD_CHARS = 512;
    private static final ClientEndpointConfig CONFIG = ClientEndpointConfig.Builder.create().extensions(List.of(new PermessageDeflate())).build();

    private final SaturationFrameServer server;
    private final long slowCharsPerSecond;
    private final Consumer<FrameReceipt> receipts;
    private final WebSocketContainer container = ContainerProvider.getWebSocketContainer();
    private final ScheduledExecutorService reconnects = Executors.newSingleThreadScheduledExecutor(
        Thread.ofPlatform().name("saturation-reconnect").daemon().factory());
    private final List<Client> clients = new CopyOnWriteArrayList<>();
    private volatile boolean closed;

    private FrameClients(SaturationFrameServer server, long slowCharsPerSecond, Consumer<FrameReceipt> receipts) {
        this.server = requireNonNull(server);
        this.slowCharsPerSecond = slowCharsPerSecond;
        this.receipts = requireNonNull(receipts);
    }

    // The clients -Dsaturation.clients and -Dsaturation.slowClients ask for, none by default, and the run then writes the frames itself; the slow
    // ones read -Dsaturation.slowClientRate characters a second.
    static Audience configured() {
        return new Audience(number(CLIENTS_PROPERTY, 0, 0), number(SLOW_CLIENTS_PROPERTY, 0, 0), number(SLOW_CLIENT_RATE_PROPERTY, SLOW_CHARS_PER_SECOND, 1));
    }

    static FrameClients serve(MonitorHub hub, Audience audience, SaturationFrameServer.FrameWritten written, Consumer<FrameReceipt> receipts) {
        return new FrameClients(SaturationFrameServer.start(hub, written), audience.slowCharsPerSecond(), receipts);
    }

    void connect(int count, boolean slow) {
        for (var index = 0; index < count; index++) {
            var client = new Client(clients.size(), slow);
            clients.add(client);
            client.connect();
        }
    }

    // Waits until every client connected so far read its first frame.
    void awaitFirstFrames() throws InterruptedException {
        for (var client : clients) {
            if (!client.firstFrame.await(FIRST_FRAME.toSeconds(), TimeUnit.SECONDS)) {
                throw new IllegalStateException("Saturation client " + client.index + " read no frame of " + server.live() + " within " + FIRST_FRAME);
            }
        }
    }

    long disconnects(boolean slow) {
        return clients.stream().filter(client -> client.slow == slow).mapToLong(client -> client.disconnects.get()).sum();
    }

    @Override
    public void close() {
        closed = true;
        reconnects.shutdownNow();
        for (var client : clients) {
            client.close();
        }
        server.close();
    }

    private static int number(String property, int fallback, int minimum) {
        var value = System.getProperty(property, String.valueOf(fallback));
        try {
            var number = Integer.parseInt(value.strip());
            if (number >= minimum) {
                return number;
            }
        } catch (NumberFormatException ignored) {
            // Reported below.
        }
        throw new IllegalArgumentException("Saturation run expects -D" + property + " to be a whole number, " + minimum + " or more; got '" + value + "'");
    }

    // How many clients read frames as they come, and how many read slowly, at how many characters a second.
    record Audience(int fast, int slow, long slowCharsPerSecond) {

        boolean any() {
            return fast + slow > 0;
        }
    }

    private final class Client extends Endpoint {

        private final int index;
        private final boolean slow;
        private final CountDownLatch firstFrame = new CountDownLatch(1);
        private final AtomicLong disconnects = new AtomicLong();
        // Only the container's thread that reads the session's messages, one after the other, touches these.
        private final StringBuilder head = new StringBuilder(HEAD_CHARS);
        private long chars;
        private long readUntilNanos;
        private volatile Session session;

        private Client(int index, boolean slow) {
            this.index = index;
            this.slow = slow;
        }

        private void connect() {
            try {
                container.connectToServer(this, CONFIG, server.live());
            } catch (DeploymentException | IOException failure) {
                if (firstFrame.getCount() > 0 && disconnects.get() == 0) {
                    throw new IllegalStateException("Saturation client " + index + " could not connect to " + server.live(), failure);
                }
                reconnectLater();
            }
        }

        @Override
        public void onOpen(Session session, EndpointConfig config) {
            this.session = session;
            head.setLength(0);
            chars = 0;
            session.addMessageHandler(String.class, (MessageHandler.Partial<String>) this::read);
        }

        @Override
        public void onClose(Session session, CloseReason reason) {
            if (!closed) {
                disconnects.incrementAndGet();
                reconnectLater();
            }
        }

        private void reconnectLater() {
            if (!closed) {
                reconnects.schedule(this::connect, RECONNECT.toMillis(), TimeUnit.MILLISECONDS);
            }
        }

        private void read(String part, boolean last) {
            if (head.length() < HEAD_CHARS) {
                head.append(part, 0, Math.min(part.length(), HEAD_CHARS - head.length()));
            }
            chars += part.length();
            if (slow) {
                readSlowly(part.length());
            }
            if (!last) {
                return;
            }
            var received = System.nanoTime();
            var matcher = HEAD.matcher(head);
            if (!matcher.find()) {
                throw new IllegalStateException("Saturation client " + index + " expected a frame naming its type and epoch; got " + head);
            }
            receipts.accept(new FrameReceipt(index, slow, Long.parseLong(matcher.group(2)), matcher.group(1).equals("snapshot"), chars, received));
            head.setLength(0);
            chars = 0;
            firstFrame.countDown();
        }

        // Paced from when the client last finished reading, so a client that waited for a frame does not read the next one faster.
        private void readSlowly(int partChars) {
            readUntilNanos = Math.max(readUntilNanos, System.nanoTime()) + partChars * 1_000_000_000L / slowCharsPerSecond;
            for (var remaining = readUntilNanos - System.nanoTime(); remaining > 0 && !Thread.currentThread().isInterrupted();
                 remaining = readUntilNanos - System.nanoTime()) {
                LockSupport.parkNanos(remaining);
            }
        }

        private void close() {
            var current = session;
            if (current == null) {
                return;
            }
            try {
                current.close();
            } catch (IOException ignored) {
                // The run ends; the connection may already be gone.
            }
        }
    }

    private record PermessageDeflate() implements Extension {

        @Override
        public String getName() {
            return "permessage-deflate";
        }

        @Override
        public List<Parameter> getParameters() {
            return List.of();
        }
    }
}
