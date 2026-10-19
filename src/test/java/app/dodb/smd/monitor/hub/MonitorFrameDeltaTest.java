package app.dodb.smd.monitor.hub;

import app.dodb.smd.monitor.hub.RecordingInstance.MutableClock;
import app.dodb.smd.monitor.hub.span.ApplicationName;
import app.dodb.smd.monitor.hub.span.Outcome;
import app.dodb.smd.monitor.json.Frame;
import app.dodb.smd.monitor.json.FrameDelta;
import app.dodb.smd.monitor.json.FrameView;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.OptionalLong;

import static app.dodb.smd.monitor.hub.RecordingInstance.OPEN;
import static app.dodb.smd.monitor.hub.RecordingInstance.with;
import static org.assertj.core.api.Assertions.assertThat;

// snapshot(e) = snapshot(e - 1) + delta(e) (W2): the changes of every epoch, applied to the JSON of the frame before, give the JSON of the frame,
// under generated load over the whole history, while one application appears later, sorted before the others, and another retires.
class MonitorFrameDeltaTest {

    private static final LoadProfile PROFILE = new LoadProfile(3, 2, 15, 4, 300, Duration.ofSeconds(20), Duration.ofSeconds(60), HubConfig.DEFAULT_MEMORY);
    private static final ApplicationName EARLY = new ApplicationName("aaa-early");
    private static final ApplicationName LEAVING = new ApplicationName("zzz-leaving");

    private final ObjectMapper mapper = new ObjectMapper();
    private final MutableClock clock = new MutableClock();

    @Test
    void deltaFrom_everyEpochOfGeneratedLoad_appliesToThePreviousSnapshotAsTheCurrentOne() {
        var config = PROFILE.hubConfig().silentAfter(Duration.ofSeconds(5)).retireAfter(Duration.ofSeconds(15)).build();
        var hub = new SynchronousHub(config, clock);
        var load = new LoadGenerator(PROFILE, 3, clock);
        var early = new RecordingInstance(EARLY, "early-1");
        var leaving = new RecordingInstance(LEAVING, "leaving-1");
        var frames = new ArrayList<Frame>();

        for (var second = 0; second < 100; second++) {
            clock.advance(Duration.ofSeconds(1));
            load.export().forEach(hub::receive);
            if (second < 20) {
                leaving.reportTo(hub, with(leaving.dispatch(OPEN), clock.instant(), new Outcome.Completed(Duration.ofMillis(1))));
            }
            if (second >= 50) {
                early.reportTo(hub, with(early.dispatch(OPEN), clock.instant(), new Outcome.Completed(Duration.ofMillis(1))));
            }
            var epoch = hub.epoch();
            frames.add(new Frame(epoch.sequence(), FrameView.from(epoch.at(), epoch.statistics(), epoch.health(), OptionalLong.empty())));
        }

        assertThat(frames.getFirst().view().applications()).extracting(application -> application.name()).contains(LEAVING.value());
        assertThat(frames.getLast().view().applications()).extracting(application -> application.name()).startsWith(EARLY.value()).doesNotContain(LEAVING.value());
        for (var index = 1; index < frames.size(); index++) {
            var delta = frames.get(index).deltaFrom(frames.get(index - 1), mapper);
            var applied = FrameDelta.apply(mapper.valueToTree(frames.get(index - 1).view()), delta.changes(), delta.shift());
            assertThat(applied).as("epoch %d", delta.epoch()).isEqualTo(mapper.valueToTree(frames.get(index).view()));
        }
    }
}
