package app.dodb.smd.monitor.hub;

import app.dodb.smd.monitor.hub.intake.SpanBatch;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

import java.util.List;
import java.util.concurrent.TimeUnit;

// Receiving 512 decoded spans into a hub in steady state, against the budget of 5 ms p99 per batch on the writer.
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class ReceiveBenchmark {

    private SteadyLoad load;
    private List<SpanBatch> batch;

    @Setup(Level.Trial)
    public void fill() {
        load = SteadyLoad.filled();
    }

    // Generating the batch is left out of the measurement.
    @Setup(Level.Invocation)
    public void nextBatch() {
        batch = load.next(LoadGenerator.MAX_EXPORT_BATCH);
    }

    @Benchmark
    public void receiveBatch(Blackhole blackhole) {
        for (var report : batch) {
            blackhole.consume(load.hub().receive(report));
        }
    }
}
