package app.dodb.smd.monitor.hub.web;

import app.dodb.smd.monitor.hub.HubConfig;
import app.dodb.smd.monitor.hub.SynchronousHub;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class FramePublisherTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final SynchronousHub hub = new SynchronousHub(HubConfig.withDefaults().build());
    private final FramePublisher publisher = new FramePublisher(hub.hub());

    // Once the publisher published the frame of the hub's first epoch, which a client connecting receives at once.
    @BeforeEach
    void start() {
        publisher.start();
        var probe = new RecordingSink(true);
        publisher.connect(probe);
        await().until(() -> !probe.messages.isEmpty());
        publisher.disconnect(probe);
    }

    @AfterEach
    void close() {
        publisher.close();
    }

    @Test
    void connect_epochsPublished_sendsTheSnapshotAndThenTheChangesOfEach() {
        var sink = new RecordingSink(true);
        publisher.connect(sink);
        var connected = hub.hub().epoch();

        hub.epoch();

        await().until(() -> sink.messages.size() == 2);
        assertThat(sink.message(0).get("type").asString()).isEqualTo("snapshot");
        assertThat(sink.message(0).get("epoch").asLong()).isEqualTo(connected.sequence());
        assertThat(sink.message(1).get("type").asString()).isEqualTo("delta");
        assertThat(sink.message(1).get("base").asLong()).isEqualTo(connected.sequence());
    }

    @Test
    void connect_asManyClientsAsTaken_refusesTheNext() {
        IntStream.range(0, FramePublisher.MAX_CLIENTS).forEach(index -> assertThat(publisher.connect(new RecordingSink(true))).isTrue());

        var refused = publisher.connect(new RecordingSink(true));

        assertThat(refused).isFalse();
    }

    @Test
    void connect_aClientDisconnected_takesAnother() {
        var sinks = IntStream.range(0, FramePublisher.MAX_CLIENTS).mapToObj(index -> new RecordingSink(true)).toList();
        sinks.forEach(publisher::connect);
        publisher.disconnect(sinks.getFirst());

        var connected = publisher.connect(new RecordingSink(true));

        assertThat(connected).isTrue();
    }

    // One send per client at a time: a client still writing skips frames, then catches up with a snapshot.
    @Test
    void publish_clientStillWritingTheLastFrame_skipsFramesAndThenReceivesTheSnapshot() {
        var slow = new RecordingSink(false);
        var fast = new RecordingSink(true);
        publisher.connect(slow);
        publisher.connect(fast);
        hub.epoch();
        await().until(() -> fast.messages.size() == 2);

        slow.complete(true);
        hub.epoch();

        await().until(() -> fast.messages.size() == 3 && slow.messages.size() == 2);
        assertThat(slow.message(1).get("type").asString()).isEqualTo("snapshot");
        assertThat(fast.message(2).get("type").asString()).isEqualTo("delta");
    }

    @Test
    void publish_sendFailed_closesTheClientWhichReceivesNoFurtherFrames() {
        var failing = new RecordingSink(false);
        var other = new RecordingSink(true);
        publisher.connect(failing);
        publisher.connect(other);

        failing.complete(false);
        hub.epoch();

        await().until(() -> other.messages.size() == 2);
        assertThat(failing.closed).isTrue();
        assertThat(failing.messages).hasSize(1);
    }

    @Test
    void close_clientsConnected_closesThemAndRefusesFurtherClients() {
        var sink = new RecordingSink(true);
        publisher.connect(sink);

        publisher.close();

        assertThat(sink.closed).isTrue();
        assertThat(publisher.connect(new RecordingSink(true))).isFalse();
    }

    // Records what the publisher sends; it reports each message written at once, or holds the report until completed.
    private final class RecordingSink implements FramePublisher.Sink {
        private final List<String> messages = new CopyOnWriteArrayList<>();
        private final boolean writesAtOnce;
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile Consumer<Boolean> pending;

        private RecordingSink(boolean writesAtOnce) {
            this.writesAtOnce = writesAtOnce;
        }

        @Override
        public void send(String message, Consumer<Boolean> sent) {
            messages.add(message);
            if (writesAtOnce) {
                sent.accept(true);
            } else {
                pending = sent;
            }
        }

        @Override
        public void close() {
            closed.set(true);
        }

        private void complete(boolean written) {
            pending.accept(written);
        }

        private JsonNode message(int index) {
            return mapper.readTree(messages.get(index));
        }
    }
}
