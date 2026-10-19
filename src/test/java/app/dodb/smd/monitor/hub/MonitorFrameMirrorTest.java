package app.dodb.smd.monitor.hub;

import app.dodb.smd.monitor.hub.RecordingInstance.MutableClock;
import app.dodb.smd.monitor.json.Frame;
import app.dodb.smd.monitor.json.FrameView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

// The page's mirror.ts applies the frames the hub sends as the hub means them: a recording of generated load, a snapshot and then the changes of
// every epoch, replayed by node, which runs the page's TypeScript as it is.
class MonitorFrameMirrorTest {

    private static final LoadProfile PROFILE = new LoadProfile(3, 2, 15, 4, 300, Duration.ofSeconds(20), Duration.ofSeconds(60), HubConfig.DEFAULT_MEMORY);
    private static final Path SCRIPT = Path.of("frontend", "scripts", "check-mirror.ts");

    private final ObjectMapper mapper = new ObjectMapper();
    private final MutableClock clock = new MutableClock();

    @Test
    void mirror_recordedFramesOfGeneratedLoad_equalsTheSnapshotOfEveryEpoch(@TempDir Path directory) throws Exception {
        assumeTrue(node("--version").exitValue() == 0, "node runs the page's TypeScript");
        var hub = new SynchronousHub(PROFILE.hubConfig().build(), clock);
        var load = new LoadGenerator(PROFILE, 5, clock);
        var frames = new ArrayList<Frame>();
        for (var second = 0; second < 80; second++) {
            clock.advance(Duration.ofSeconds(1));
            load.export().forEach(hub::receive);
            var epoch = hub.epoch();
            frames.add(new Frame(epoch.sequence(), FrameView.from(epoch.at(), epoch.statistics(), epoch.health(), OptionalLong.empty())));
        }
        var messages = new ArrayList<Object>();
        messages.add(frames.getFirst().snapshot());
        for (var index = 1; index < frames.size(); index++) {
            messages.add(frames.get(index).deltaFrom(frames.get(index - 1), mapper));
        }
        var recording = directory.resolve("recording.json");
        Files.writeString(recording, mapper.writeValueAsString(new Recording(messages, frames.stream().map(Frame::view).toList())));

        var replay = node(SCRIPT.toString(), recording.toString());

        assertThat(output(replay)).contains("Mirrored " + frames.size() + " messages");
        assertThat(replay.exitValue()).isZero();
    }

    private static Process node(String... arguments) throws IOException, InterruptedException {
        var command = new ArrayList<String>();
        command.add("node");
        command.addAll(List.of(arguments));
        Process process;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
        } catch (IOException missing) {
            return new ProcessBuilder(System.getProperty("os.name").startsWith("Windows") ? List.of("cmd", "/c", "exit 1") : List.of("false")).start();
        }
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).as("node finished within a minute").isTrue();
        return process;
    }

    private static String output(Process process) throws IOException {
        return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    private record Recording(List<Object> messages, List<FrameView> frames) {
    }
}
