package app.dodb.smd.monitor.hub;

import app.dodb.smd.monitor.json.FailureCausesView;
import app.dodb.smd.monitor.json.TapePageView;
import app.dodb.smd.monitor.json.TimelineWindowView;
import app.dodb.smd.monitor.json.TracePageView;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.TimeUnit;

// What naming an answer by an ETag adds to writing it, for the answers a page asks for every second at the design target: the answer written as
// JSON alone, and written with the hash of its content but the sequence, as HubQueries answers it. Each is written once per epoch and query, and
// shared by every page asking it.
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class AnswerBenchmark {

    @Param({"tape", "traces", "failures", "timeline"})
    public String answer;

    private final ObjectMapper mapper = new ObjectMapper();
    private Object view;

    @Setup(Level.Trial)
    public void fill() {
        var epoch = SteadyLoad.filled().hub().epoch();
        view = switch (answer) {
            case "tape" -> TapePageView.from(epoch.session(), epoch.tape(QueryBenchmark.TAPE));
            case "traces" -> TracePageView.from(epoch.session(), epoch.traces(QueryBenchmark.TRACES));
            case "failures" -> FailureCausesView.from(epoch.session(), epoch.failures(epoch.statistics().applications().stream()
                .flatMap(application -> application.failures().stream().map(failure -> Map.entry(failure,
                    new FailureCause.Step(application.application(), failure.failure().stage(), failure.failure().failureType()))))
                .sorted(Comparator.comparingLong((Map.Entry<RaisedFailureStatistics, FailureCause.Step> entry) -> entry.getKey().window()).reversed())
                .limit(QueryBenchmark.SHOWN_CAUSES)
                .map(Map.Entry::getValue)
                .toList()));
            case "timeline" -> TimelineWindowView.from(epoch.session(), epoch.timeline(QueryBenchmark.TIMELINE));
            default -> throw new IllegalArgumentException("Unknown answer " + answer);
        };
        System.out.printf("%nThe %s answer of the steady load takes %,d bytes%n", answer, written().length());
    }

    @Benchmark
    public String written() {
        return mapper.writeValueAsString(view);
    }

    @Benchmark
    public String versioned() {
        ObjectNode content = mapper.valueToTree(view);
        var body = mapper.writeValueAsString(content);
        content.remove("sequence");
        return hash(mapper.writeValueAsString(content)) + body;
    }

    private static String hash(String version) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(version.getBytes(StandardCharsets.UTF_8)), 0, 16);
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
