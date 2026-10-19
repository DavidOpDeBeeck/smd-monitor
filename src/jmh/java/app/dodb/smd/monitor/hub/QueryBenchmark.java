package app.dodb.smd.monitor.hub;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

// The main queries of the page in the system scope, against the budget of 50 ms p99 per query, over one epoch. See RelinkBenchmark for the first
// query of an epoch.
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class QueryBenchmark {

    static final int SHOWN_CAUSES = 20;
    static final TapeQuery TAPE = new TapeQuery(InvocationFilter.EVERY_INVOCATION, Duration.ZERO, Optional.empty(), 100);
    static final TraceQuery TRACES = new TraceQuery(Optional.empty(), TypeSelection.EVERY_TYPE, Set.of(), Set.of(), false, "", TraceOrder.NEWEST, Optional.empty(), 100);
    static final TimelineQuery TIMELINE = new TimelineQuery(InvocationFilter.EVERY_INVOCATION, Duration.ZERO, Duration.ofMinutes(1), 2_000);

    private Epoch epoch;
    // The causes the Failures view shows first: the most frequent of the window.
    private List<FailureCause.Step> shown;

    @Setup(Level.Trial)
    public void fill() {
        epoch = SteadyLoad.filled().hub().epoch();
        epoch.tape(TAPE);
        var counted = epoch.statistics().applications().stream()
            .flatMap(application -> application.failures().stream().map(failure -> Map.entry(failure,
                new FailureCause.Step(application.application(), failure.failure().stage(), failure.failure().failureType()))))
            .toList();
        shown = counted.stream()
            .sorted(Comparator.comparingLong((Map.Entry<RaisedFailureStatistics, FailureCause.Step> entry) -> entry.getKey().window()).reversed())
            .limit(SHOWN_CAUSES)
            .map(Map.Entry::getValue)
            .toList();
        System.out.printf("Failures of the steady load: %,d causes counted; the %d shown retain %,d occurrences%n", counted.size(), shown.size(),
            epoch.failures(shown).causes().stream().mapToInt(FailureCause::retained).sum());
    }

    @Benchmark
    public TapePage tape() {
        return epoch.tape(TAPE);
    }

    @Benchmark
    public TracePage traces() {
        return epoch.traces(TRACES);
    }

    @Benchmark
    public Failures failures() {
        return epoch.failures(shown);
    }

    @Benchmark
    public TimelineWindow timeline() {
        return epoch.timeline(TIMELINE);
    }
}
