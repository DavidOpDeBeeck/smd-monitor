package app.dodb.smd.monitor.hub;

import app.dodb.smd.monitor.json.TraceDetailView;
import app.dodb.smd.monitor.json.TracePageView;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

// What one page showing Traces asks of the hub at every epoch, answered as the endpoints answer it, in JSON: the list loaded from the top as far as
// it reached, a page or the most the list holds, and the detail of the selected trace with the ETag of its content. The detail is of the trace the
// page selects first, the newest of more than one message, and of the largest trace.
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class TraceViewBenchmark {

    static final TraceQuery FIRST_PAGE = traces(TraceOrder.NEWEST, 100);
    static final TraceQuery FULL_LIST = traces(TraceOrder.NEWEST, TraceQuery.MAX_LIMIT);

    private final ObjectMapper mapper = new ObjectMapper();
    private Epoch epoch;
    private InvocationId first;
    private InvocationId largest;

    @Setup(Level.Trial)
    public void fill() {
        epoch = SteadyLoad.filled().hub().epoch();
        first = epoch.traces(FIRST_PAGE).traces().stream().filter(trace -> trace.messages() > 1).findFirst().orElseThrow().root().id();
        largest = epoch.traces(traces(TraceOrder.LARGEST, 1)).traces().getFirst().root().id();
        System.out.printf("%nTraces of the steady load: a page of 100 takes %,d bytes and the full list of %,d takes %,d; the detail of the first selected "
                + "trace of %,d members takes %,d bytes, of the largest of %,d members %,d%n", bytes(firstPage()), TraceQuery.MAX_LIMIT, bytes(fullList()),
            epoch.trace(first).orElseThrow().members().size(), bytes(firstDetail()), epoch.trace(largest).orElseThrow().members().size(),
            bytes(largestDetail()));
    }

    @Benchmark
    public String firstPage() {
        return mapper.writeValueAsString(TracePageView.from(epoch.session(), epoch.traces(FIRST_PAGE)));
    }

    @Benchmark
    public String fullList() {
        return mapper.writeValueAsString(TracePageView.from(epoch.session(), epoch.traces(FULL_LIST)));
    }

    @Benchmark
    public String firstDetail() {
        return detail(first);
    }

    @Benchmark
    public String largestDetail() {
        return detail(largest);
    }

    // As MonitorHubQueries answers a trace: its JSON and the hash of its content, which names the ETag.
    private String detail(InvocationId member) {
        var view = TraceDetailView.from(epoch.session(), epoch.trace(member).orElseThrow());
        var json = mapper.writeValueAsString(view);
        return hash(mapper.writeValueAsString(List.of(view.trace(), view.members()))) + json;
    }

    private static TraceQuery traces(TraceOrder order, int limit) {
        return new TraceQuery(InvocationFilter.EVERY_INVOCATION, false, order, Optional.empty(), limit);
    }

    private static int bytes(String json) {
        return json.getBytes(StandardCharsets.UTF_8).length;
    }

    private static String hash(String version) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(version.getBytes(StandardCharsets.UTF_8)), 0, 16);
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
