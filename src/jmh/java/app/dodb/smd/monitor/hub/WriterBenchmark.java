package app.dodb.smd.monitor.hub;

import app.dodb.smd.monitor.hub.intake.SpanBatch;
import app.dodb.smd.monitor.hub.span.ApplicationName;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;

// One second of the writer's work in steady state: receiving what every instance exported in that second, then the tick that publishes the
// epoch. Its time per operation is the share of a core the writer needs; above 1 s per operation, ingest falls behind and the queues fill.
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class WriterBenchmark {

    private static final int SAMPLED_SECONDS = 60;

    private SteadyLoad load;
    private List<SpanBatch> second;

    // While the writer needs less than a second per second, a queue holds at most what its application exported in about a second: what arrived
    // since the writer last drained it, during a tick and the drain before it.
    @Setup(Level.Trial)
    public void fill() {
        load = SteadyLoad.filled();
        var peak = new HashMap<ApplicationName, Integer>();
        for (var index = 0; index < SAMPLED_SECONDS; index++) {
            var exported = load.nextSecond();
            var perApplication = new HashMap<ApplicationName, Integer>();
            exported.forEach(batch -> perApplication.merge(batch.application(), batch.size(), Integer::sum));
            perApplication.forEach((application, spans) -> peak.merge(application, spans, Math::max));
            exported.forEach(load::receive);
        }
        var share = load.budget().queuedSpans() / Math.max(1, peak.size());
        var highest = peak.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        System.out.printf("%nWriter queues of the steady load: an application exports at most %,d spans in a second, %.1f%% of its share of %,d spans%n",
            highest, 100.0 * highest / share, share);
    }

    // Exporting the second is left out of the measurement.
    @Setup(Level.Invocation)
    public void nextSecond() {
        second = load.nextSecond();
    }

    @Benchmark
    public Epoch oneSecond() {
        second.forEach(load::receive);
        return load.hub().epoch();
    }
}
