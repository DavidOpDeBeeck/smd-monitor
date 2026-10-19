package app.dodb.smd.monitor.hub;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.util.concurrent.TimeUnit;

// The first tape query of a new epoch, after the hub retained one more invocation: what the page pays once per epoch, about once a second. Up to
// S3 it linked everything the epoch retained first; since S4 the writer links traces as spans arrive.
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class RelinkBenchmark {

    private SteadyLoad load;
    private Epoch epoch;

    @Setup(Level.Trial)
    public void fill() {
        load = SteadyLoad.filled();
    }

    // Receiving the span and building the epoch are left out of the measurement.
    @Setup(Level.Invocation)
    public void retainAnother() {
        load.next(1).forEach(load::receive);
        epoch = load.hub().epoch();
    }

    @Benchmark
    public TapePage firstTapeOfAnEpoch() {
        return epoch.tape(QueryBenchmark.TAPE);
    }
}
