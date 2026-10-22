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
    void intervalFor_upToSixteenClients_isOneEpoch() {
        assertThat(FramePublisher.intervalFor(0)).isEqualTo(1);
        assertThat(FramePublisher.intervalFor(16)).isEqualTo(1);
    }

    @Test
    void intervalFor_eachSixteenClientsMore_addsAnEpoch() {
        assertThat(FramePublisher.intervalFor(17)).isEqualTo(2);
        assertThat(FramePublisher.intervalFor(32)).isEqualTo(2);
        assertThat(FramePublisher.intervalFor(33)).isEqualTo(3);
        assertThat(FramePublisher.intervalFor(64)).isEqualTo(4);
    }

    @Test
    void intervalFor_manyClients_isAtMostFiveEpochs() {
        assertThat(FramePublisher.intervalFor(65)).isEqualTo(5);
        assertThat(FramePublisher.intervalFor(1_000)).isEqualTo(5);
    }

    // W1: with an interval of two epochs, the epoch between two frames is left out, and the next frame carries its changes too.
    @Test
    void publish_intervalOfTwoEpochs_leavesTheEpochBetweenOutAndSendsTheChangesSinceTheFrameBefore() {
        var spaced = new FramePublisher(hub.hub(), new MonitorFrames(), FramePublisher.MAX_CLIENTS, clients -> 2);
        spaced.start();
        try {
            var sink = new RecordingSink(true);
            spaced.connect(sink);
            await().until(() -> sink.messages.size() == 1);
            var first = sink.message(0).get("epoch").asLong();

            hub.epoch();
            var second = hub.epoch();

            await().until(() -> sink.messages.size() == 2);
            assertThat(sink.message(1).get("type").asString()).isEqualTo("delta");
            assertThat(sink.message(1).get("base").asLong()).isEqualTo(first);
            assertThat(sink.message(1).get("epoch").asLong()).isEqualTo(second.sequence());
        } finally {
            spaced.close();
        }
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

    // One send per client at a time: a client still writing skips frames, then catches up with the changes since the frame it was sent.
    @Test
    void publish_clientStillWritingTheLastFrame_skipsFramesAndThenReceivesTheChangesSinceItsFrame() {
        var slow = new RecordingSink(false);
        var fast = new RecordingSink(true);
        publisher.connect(slow);
        publisher.connect(fast);
        hub.epoch();
        await().until(() -> fast.messages.size() == 2);

        slow.complete(true);
        hub.epoch();

        await().until(() -> fast.messages.size() == 3 && slow.messages.size() == 2);
        assertThat(slow.message(1).get("type").asString()).isEqualTo("delta");
        assertThat(slow.message(1).get("base").asLong()).isEqualTo(slow.message(0).get("epoch").asLong());
        assertThat(fast.message(2).get("type").asString()).isEqualTo("delta");
    }

    @Test
    void publish_clientFurtherBehindThanTheFrameKeepsChangesFor_receivesTheSnapshot() {
        var slow = new RecordingSink(false);
        var fast = new RecordingSink(true);
        publisher.connect(slow);
        publisher.connect(fast);
        for (var epoch = 1; epoch <= PublishedFrame.BASES; epoch++) {
            hub.epoch();
            var received = epoch + 1;
            await().until(() -> fast.messages.size() == received);
        }

        slow.complete(true);
        hub.epoch();

        await().until(() -> slow.messages.size() == 2);
        assertThat(slow.message(1).get("type").asString()).isEqualTo("snapshot");
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
        private final List<byte[]> messages = new CopyOnWriteArrayList<>();
        private final boolean writesAtOnce;
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile Consumer<Boolean> pending;

        private RecordingSink(boolean writesAtOnce) {
            this.writesAtOnce = writesAtOnce;
        }

        @Override
        public void send(byte[] message, Consumer<Boolean> sent) {
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
