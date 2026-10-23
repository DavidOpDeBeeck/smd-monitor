package app.dodb.smd.monitor.hub;

import app.dodb.smd.monitor.hub.web.HubQueries;
import app.dodb.smd.monitor.hub.web.QueryAnswer;
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
// query of an epoch. The rankings the hub answers for the type table and the failures view are measured as a page asks them, written as JSON with
// their ETag, each with answers of its own, as the first page asking them in an epoch does.
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class QueryBenchmark {

    static final int SHOWN_CAUSES = 20;
    static final TapeQuery TAPE = new TapeQuery(InvocationFilter.EVERY_INVOCATION, Duration.ZERO, Optional.empty(), 100);
    static final TraceQuery TRACES = new TraceQuery(InvocationFilter.EVERY_INVOCATION, false, TraceOrder.NEWEST, Optional.empty(), 100);
    static final TimelineQuery TIMELINE = new TimelineQuery(InvocationFilter.EVERY_INVOCATION, Duration.ZERO, Duration.ofMinutes(1), 2_000);

    private SynchronousHub hub;
    private HubQueries queries;
    private Epoch epoch;
    // Most pages look at one application, as the pages of SaturationPages do after the first four.
    private TapeQuery tapeOfOne;
    private TimelineQuery timelineOfOne;
    // The causes the Failures view shows first: the most frequent of the window, and those of one application.
    private List<FailureCause.Step> shown;
    private List<FailureCause.Step> shownOfOne;

    @Setup(Level.Trial)
    public void fill() {
        hub = SteadyLoad.filled().hub();
        epoch = hub.epoch();
        epoch.tape(TAPE);
        var one = new InvocationFilter(Optional.of(epoch.statistics().applications().getFirst().application()), Set.of(), TypeSelection.EVERY_TYPE, Set.of(), "");
        tapeOfOne = new TapeQuery(one, Duration.ZERO, Optional.empty(), 100);
        timelineOfOne = new TimelineQuery(one, Duration.ZERO, Duration.ofMinutes(1), 2_000);
        var counted = epoch.statistics().applications().stream()
            .flatMap(application -> application.failures().stream().map(failure -> Map.entry(failure,
                new FailureCause.Step(application.application(), failure.failure().stage(), failure.failure().failureType()))))
            .toList();
        var ranked = Comparator.comparingLong((Map.Entry<RaisedFailureStatistics, FailureCause.Step> entry) -> entry.getKey().window()).reversed();
        shown = counted.stream().sorted(ranked).limit(SHOWN_CAUSES).map(Map.Entry::getValue).toList();
        shownOfOne = counted.stream().filter(entry -> entry.getValue().application().equals(one.scope().orElseThrow())).sorted(ranked).limit(SHOWN_CAUSES)
            .map(Map.Entry::getValue).toList();
        System.out.printf("Failures of the steady load: %,d causes counted; the %d shown retain %,d occurrences%n", counted.size(), shown.size(),
            epoch.failures(shown).causes().stream().mapToInt(FailureCause::retained).sum());
    }

    @Setup(Level.Invocation)
    public void answerAfresh() {
        queries = new HubQueries(hub.hub(), Integer.MAX_VALUE);
    }

    @Benchmark
    public QueryAnswer typeFigures() {
        return queries.typeFigures(null, List.of(), List.of(), "rate", "100");
    }

    @Benchmark
    public QueryAnswer causes() {
        return queries.causes(null, "ranked", String.valueOf(SHOWN_CAUSES));
    }

    @Benchmark
    public TapePage tape() {
        return epoch.tape(TAPE);
    }

    @Benchmark
    public TapePage tapeOfOneApplication() {
        return epoch.tape(tapeOfOne);
    }

    @Benchmark
    public TimelineWindow timelineOfOneApplication() {
        return epoch.timeline(timelineOfOne);
    }

    @Benchmark
    public TracePage traces() {
        return epoch.traces(TRACES);
    }

    // The first failures query of an epoch walks its failed specimens; the others of the epoch read only the occurrences of their causes.
    @Benchmark
    public Failures failures(FreshEpoch fresh) {
        return fresh.epoch.failures(shown);
    }

    @Benchmark
    public Failures failuresOfOneApplicationAfterTheFirst() {
        return epoch.failures(shownOfOne);
    }

    @Benchmark
    public TimelineWindow timeline() {
        return epoch.timeline(TIMELINE);
    }

    // An epoch no query asked yet, of the same specimens.
    @State(Scope.Thread)
    public static class FreshEpoch {

        private Epoch epoch;

        @Setup(Level.Invocation)
        public void tick(QueryBenchmark benchmark) {
            epoch = benchmark.hub.epoch();
        }
    }
}
