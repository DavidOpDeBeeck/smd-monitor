package app.dodb.smd.monitor.hub.spring;

import app.dodb.smd.monitor.hub.HubConfig;
import app.dodb.smd.monitor.hub.SynchronousHub;
import app.dodb.smd.monitor.json.Frame;
import app.dodb.smd.monitor.json.FrameView;
import app.dodb.smd.monitor.json.Protocol;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;

class PublishedFrameTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final SynchronousHub hub = new SynchronousHub(HubConfig.withDefaults().build());
    private final Frame base = frame();
    private final Frame next = frame();

    @Test
    void messageFor_clientThatReceivedTheBase_isTheChangesSinceIt() {
        var published = new PublishedFrame(next, base, mapper);

        var message = mapper.readTree(published.messageFor(base.epoch()));

        assertThat(message.get("type").asString()).isEqualTo("delta");
        assertThat(message.get("base").asLong()).isEqualTo(base.epoch());
        assertThat(message.get("epoch").asLong()).isEqualTo(next.epoch());
        assertThat(message.get("protocol").asInt()).isEqualTo(Protocol.VERSION);
    }

    // W3: a client that missed the frame of the base, or just connected, recovers with the snapshot.
    @Test
    void messageFor_clientThatMissedTheBase_isTheSnapshot() {
        var published = new PublishedFrame(next, base, mapper);

        var message = mapper.readTree(published.messageFor(base.epoch() - 1));

        assertThat(message.get("type").asString()).isEqualTo("snapshot");
        assertThat(message.get("epoch").asLong()).isEqualTo(next.epoch());
        assertThat(message.get("frame").get("endEpochSecond").asLong()).isEqualTo(next.view().endEpochSecond());
    }

    @Test
    void messageFor_firstFramePublished_isTheSnapshotForEveryClient() {
        var published = new PublishedFrame(base, null, mapper);

        assertThat(mapper.readTree(published.messageFor(base.epoch() - 1)).get("type").asString()).isEqualTo("snapshot");
    }

    @Test
    void messageFor_severalClients_writesEachMessageOnce() {
        var published = new PublishedFrame(next, base, mapper);

        assertThat(published.messageFor(base.epoch())).isSameAs(published.messageFor(base.epoch()));
        assertThat(published.messageFor(-1)).isSameAs(published.messageFor(-1));
    }

    private Frame frame() {
        var epoch = hub.epoch();
        return new Frame(epoch.sequence(), FrameView.from(epoch.at(), epoch.statistics(), epoch.health(), OptionalLong.empty()));
    }
}
