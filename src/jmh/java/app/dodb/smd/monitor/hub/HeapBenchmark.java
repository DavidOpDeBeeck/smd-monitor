package app.dodb.smd.monitor.hub;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

// The heap the hub takes at the steady load against its memory budget: the heap used after a collection with the hub, less that without it.
// The measurement is printed by the setup; the benchmark itself does nothing.
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SingleShotTime)
@Warmup(iterations = 0)
@Measurement(iterations = 1)
public class HeapBenchmark {

    private static final long MEGABYTE = 1024 * 1024;

    // The budget in megabytes: the design target's; -p budgetMegabytes=1024,4096 measures others.
    @Param({"2048"})
    public long budgetMegabytes;

    @Setup(Level.Trial)
    public void measure() {
        var load = SteadyLoad.filled(budgetMegabytes * MEGABYTE);
        var accounted = load.hub().health().memory().bytes();
        var budget = load.budget().total();
        var with = usedAfterCollection();
        load.forgetHub();
        var without = usedAfterCollection();
        System.out.printf("%nHeap of the hub at the steady load: %,d MB after a collection, %,d MB accounted, of a %,d MB budget%n",
            (with - without) / MEGABYTE, accounted / MEGABYTE, budget / MEGABYTE);
    }

    @Benchmark
    public void nothing() {
    }

    private static long usedAfterCollection() {
        for (var collection = 0; collection < 3; collection++) {
            System.gc();
        }
        var runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }
}
