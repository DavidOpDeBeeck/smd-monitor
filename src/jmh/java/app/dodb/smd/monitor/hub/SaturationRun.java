package app.dodb.smd.monitor.hub;

import app.dodb.smd.monitor.hub.RecordingInstance.MutableClock;
import app.dodb.smd.monitor.hub.budget.Footprint;
import app.dodb.smd.monitor.hub.budget.MemoryBudget;
import app.dodb.smd.monitor.hub.health.ApplicationHealth;
import app.dodb.smd.monitor.hub.health.HealthReason;
import app.dodb.smd.monitor.hub.intake.SpanBatch;
import app.dodb.smd.monitor.json.Frame;
import app.dodb.smd.monitor.json.FrameView;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;
import java.util.function.ToDoubleFunction;
import java.util.function.ToLongFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Objects.requireNonNull;

// An open-loop saturation run of the hub as it runs: admission and the intake queues on submitting threads, its writer on its own thread, and a
// publisher writing the frame of every epoch as MonitorFrames does. The run fills the history at the first rate faster than real time, waiting
// whenever a queue is full, then offers each second's exports spread over that second at the time they are due, whether or not the hub kept up,
// as exporters do. A batch the hub answers busy is counted and not sent again. Generators of disjoint ranges of applications produce the load on
// their own threads, so it keeps up with the hub. Every epoch is sampled; each step is summarized without its first seconds, while the load and
// the queues settle at the new rate. A step the writer clearly keeps up with ends early, and the run stops at the first step that fails.
//
// With clients (FrameClients), the hub's own WebSocket publishes the frames instead, and the run also measures what the clients read of them and
// how late. Clients that read frames as they come connect before the run's clock starts and read the filled hub's snapshot; slow ones connect as it
// starts, as a page that opens on a busy hub.
public final class SaturationRun {

    private static final long SEED = 20261017;
    private static final int GENERATORS = 4;
    private static final int SUBMITTERS = 8;
    private static final long SPREAD_NANOS = Duration.ofMillis(900).toNanos();
    private static final int GENERATED_AHEAD = 3;
    private static final int SETTLING_SECONDS = 5;
    // A step that keeps up over its first seconds after settling, with the writer never busy half of a second, ends as soon as the generators
    // allow: a writer that waits for batches that often drains its queues, so the rest of the step would measure the same.
    private static final int PROBE_SECONDS = 5;
    private static final double EASY_WRITER_BUSY = 0.5;
    private static final long GENERATOR_LATE_NANOS = Duration.ofMillis(250).toNanos();
    private static final long MEGABYTE = 1024 * 1024;
    private static final int DELIVERY_COLUMNS = 13;
    // The run every scenario of one invocation belongs to, named by when it started and the commit; the parent passes it on to the JVM of each.
    private static final String RUN_PROPERTY = "saturation.run";
    // What the run ran on, from -PsaturationMachine; described from the JVM when absent.
    private static final String MACHINE_PROPERTY = "saturation.machine";

    private final SaturationScenario scenario;
    private final Path output;
    private final SaturationProgress progress;
    private final SaturationSchedule schedule;
    private final long fillSeconds;
    private final Instant origin = new MutableClock().instant();
    private final PacedClock clock = new PacedClock(origin);
    private final MonitorHub hub;
    private final int queueCapacity;
    private final List<Partition> partitions;
    private final Random shuffling = new Random(SEED);
    private final LongAdder offered = new LongAdder();
    private final LongAdder accepted = new LongAdder();
    private final LongAdder busy = new LongAdder();
    private final LongAdder refused = new LongAdder();
    private final AtomicLong submitLateNanos = new AtomicLong();
    private final Map<Long, Long> generatorLateNanos = new ConcurrentHashMap<>();
    private final Map<Long, FrameSample> frames = new ConcurrentHashMap<>();
    private final List<Sample> samples = new CopyOnWriteArrayList<>();
    private final FrameClients.Audience audience = FrameClients.configured();
    private final List<FrameReceipt> receipts = new CopyOnWriteArrayList<>();
    // Read from the clients as the run ends, before they close.
    private long fastDisconnects;
    private long slowDisconnects;

    private SaturationRun(SaturationScenario scenario, Optional<Duration> fill, Path output, SaturationProgress progress) {
        this.scenario = requireNonNull(scenario);
        this.output = requireNonNull(output);
        this.progress = requireNonNull(progress);
        this.schedule = new SaturationSchedule(scenario.rates(), scenario.stepSeconds());
        this.fillSeconds = fill.orElse(scenario.profile().history()).toSeconds();
        var budget = MemoryBudget.of(scenario.profile().memory());
        this.queueCapacity = budget.queuedSpans();
        this.hub = new MonitorHub(scenario.profile().hubConfig().build(), clock, budget, Runtime.getRuntime().maxMemory());
        this.partitions = partitions(scenario, schedule, origin.getEpochSecond() + fillSeconds);
    }

    // Arguments: the comma-separated scenario selection (default "all"; see SaturationScenario.selected), the output directory (default build/saturation), how long a history
    // to fill before measuring as an ISO-8601 duration such as PT2M ("-" or blank: the scenario's whole history), and optionally the
    // comma-separated rates to offer instead of the scenario's. Several scenarios run one after the other, each in a JVM of its own with this
    // JVM's options, so one run's heap and compiled code never shape the next.
    public static void main(String[] args) throws Exception {
        // The tables group thousands with commas, as the design doc does, whatever the machine's locale.
        Locale.setDefault(Locale.ROOT);
        var scenarios = SaturationScenario.selected(args.length > 0 ? args[0] : "all");
        var output = Path.of(args.length > 1 ? args[1] : "build/saturation");
        var fill = args.length > 2 && !args[2].equals("-") ? args[2] : "";
        var rates = args.length > 3 ? args[3] : "";
        Files.createDirectories(output);
        if (System.getProperty(RUN_PROPERTY) == null) {
            System.setProperty(RUN_PROPERTY, Instant.now().truncatedTo(ChronoUnit.SECONDS) + " " + commit());
        }
        if (scenarios.size() > 1) {
            var plan = new SaturationPlan(scenarios, output);
            for (var index = 0; index < scenarios.size(); index++) {
                var progress = plan.progressOf(index);
                runInOwnJvm(scenarios.get(index), output, fill, rates, progress);
                plan.finished(progress);
            }
            return;
        }
        var scenario = rates.isBlank() ? scenarios.getFirst() : scenarios.getFirst().withRates(rates);
        var run = new SaturationRun(scenario, fill.isBlank() ? Optional.empty() : Optional.of(Duration.parse(fill)), output,
            SaturationProgress.of(scenario, output));
        run.write(run.run());
    }

    // The classpath goes through an argument file, as it can exceed the length of a Windows command line.
    static void runInOwnJvm(SaturationScenario scenario, Path output, String fill, String rates, SaturationProgress progress)
        throws IOException, InterruptedException {
        var java = ProcessHandle.current().info().command()
            .orElseThrow(() -> new IllegalStateException("Saturation run " + scenario.id() + " cannot find the java command of this JVM to start its own"));
        var classpath = output.resolve("classpath.args");
        Files.writeString(classpath, "-cp \"" + System.getProperty("java.class.path").replace("\\", "\\\\") + "\"");
        var command = new ArrayList<>(List.of(java));
        command.addAll(ManagementFactory.getRuntimeMXBean().getInputArguments());
        command.add("-D" + RUN_PROPERTY + "=" + System.getProperty(RUN_PROPERTY));
        command.add(progress.jvmArgument());
        command.addAll(List.of("@" + classpath, SaturationRun.class.getName(), scenario.id(), output.toString(), fill.isBlank() ? "-" : fill));
        if (!rates.isBlank()) {
            command.add(rates);
        }
        var exit = new ProcessBuilder(command).inheritIO().start().waitFor();
        if (exit != 0) {
            throw new IllegalStateException("Saturation run " + scenario.id() + " exited with " + exit);
        }
    }

    // Each generator takes a range of the applications and its share of the rate; the traces that continue in a next application stay in its range.
    private static List<Partition> partitions(SaturationScenario scenario, SaturationSchedule schedule, long fillEnd) {
        var profile = scenario.profile();
        var count = Math.min(GENERATORS, profile.applications());
        var partitions = new ArrayList<Partition>();
        var first = 0;
        for (var index = 0; index < count; index++) {
            var applications = profile.applications() / count + (index < profile.applications() % count ? 1 : 0);
            var share = (double) applications / profile.applications();
            var part = new LoadProfile(applications, profile.instances(), profile.types(), profile.groups(), profile.spansPerSecond(), profile.window(),
                profile.history(), profile.memory());
            var clock = new MutableClock();
            var load = new LoadGenerator(part, SEED + index, clock, second -> (int) Math.round(share * schedule.rateAt(second - fillEnd)), first);
            partitions.add(new Partition(load, clock));
            first += applications;
        }
        return List.copyOf(partitions);
    }

    private List<SaturationStep> run() throws InterruptedException {
        System.out.printf("%n== %s (%s)%n%s; queues of %,d spans (%,d per application), %d generators%n", scenario.name(), scenario.id(),
            scenario.description(), queueCapacity, queueCapacity / scenario.profile().applications(), partitions.size());
        progress.report(output, "filling %d s of history, then up to %d rates from %,d spans/s".formatted(fillSeconds, scenario.rates().size(),
            scenario.rates().getFirst()));
        hub.start();
        var writer = writerThread();
        fill(writer);
        var threads = new ArrayList<Thread>();
        for (var partition : partitions) {
            partition.ahead.put(partition.generate());
            partition.ahead.put(partition.generate());
            threads.add(Thread.ofPlatform().name("saturation-generator-" + threads.size()).daemon().start(partition::generateAhead));
        }
        var clients = audience.any() ? Optional.of(FrameClients.serve(hub, audience, this::written, receipts::add)) : Optional.<FrameClients>empty();
        var submitters = Executors.newFixedThreadPool(SUBMITTERS, Thread.ofPlatform().name("saturation-submitter-", 0).daemon().factory());
        try {
            if (clients.isPresent()) {
                clients.get().connect(audience.fast(), false);
                clients.get().awaitFirstFrames();
            }
            clock.start();
            clients.ifPresent(each -> each.connect(audience.slow(), true));
            threads.add(Thread.ofPlatform().name("saturation-sampler").daemon().start(() -> sample(writer)));
            if (clients.isEmpty()) {
                threads.add(Thread.ofPlatform().name("saturation-publisher").daemon().start(this::publish));
            }
            return offer(submitters);
        } finally {
            submitters.shutdownNow();
            threads.forEach(Thread::interrupt);
            for (var thread : threads) {
                thread.join();
            }
            submitters.awaitTermination(5, TimeUnit.SECONDS);
            clients.ifPresent(each -> {
                fastDisconnects = each.disconnects(false);
                slowDisconnects = each.disconnects(true);
                each.close();
            });
            hub.close();
        }
    }

    // Closed loop, as fast as the hub takes it, on a clock that stands still meanwhile: each second's exports are submitted at the start of that
    // second, waiting while a queue is full, and the epoch after it is awaited. The writer schedules its next tick from the clock once it published
    // an epoch, so the clock moves on only once the writer waits again; moved earlier, the tick would fall a second beyond a clock that stands still.
    private void fill(Thread writer) throws InterruptedException {
        var started = System.nanoTime();
        var spans = 0L;
        for (var second = 1; second <= fillSeconds; second++) {
            var before = hub.epoch().sequence();
            clock.skipTo(origin.plusSeconds(second));
            for (var partition : partitions) {
                for (var batch : partition.generate()) {
                    while (hub.submit(batch) instanceof Submission.Busy) {
                        LockSupport.parkNanos(Duration.ofMillis(1).toNanos());
                    }
                    spans += batch.size();
                }
            }
            clock.skipTo(origin.plusSeconds(second).plus(HubWriter.TICK_OFFSET).plusMillis(10));
            hub.awaitEpochAfter(before);
            while (writer.getState() == Thread.State.RUNNABLE) {
                LockSupport.parkNanos(Duration.ofMillis(1).toNanos());
            }
            if (second % 60 == 0 || second == fillSeconds) {
                var elapsed = Duration.ofNanos(System.nanoTime() - started);
                progress.report(output, "filled %d of %d s: %,d spans in %d s, %,d spans/s".formatted(second, fillSeconds, spans, elapsed.toSeconds(),
                    spans * 1_000_000_000L / Math.max(1, elapsed.toNanos())));
            }
        }
    }

    // Open loop: each second's batches are offered spread over the second at the time they are due, from the submitting threads. Seconds count
    // as the schedule's, from the first after the fill. Seconds are measured once two more were offered, by when the epoch of the last one was
    // published, so the pacer never pauses: a step once the next one offered its second second, and a step's first seconds, to end it early,
    // once two seconds after them were offered.
    private List<SaturationStep> offer(ExecutorService submitters) throws InterruptedException {
        var steps = new ArrayList<SaturationStep>();
        var last = scenario.rates().size() - 1;
        for (var second = 0L; ; second++) {
            offerSecond(runSecond(second), submitters);
            var step = schedule.stepAt(second);
            var first = schedule.firstSecondOf(step);
            if (step > 0 && second == first + 1) {
                var summarized = summarize(step - 1);
                steps.add(summarized);
                if (summarized.verdict().failed()) {
                    return steps;
                }
            }
            if (step < last && second == first + SETTLING_SECONDS + PROBE_SECONDS + 1 && keepsUpEasily(step)) {
                schedule.endStepAfter(step, second);
            }
            if (step == last && second == schedule.lastSecondOf(last)) {
                break;
            }
        }
        parkUntil(clock.nanoTimeAt(origin.plusSeconds(runSecond(schedule.lastSecondOf(last)) + 2).plus(HubWriter.TICK_OFFSET).plusMillis(500)));
        steps.add(summarize(last));
        return steps;
    }

    // The step's first seconds after settling, judged as a step of their own.
    private boolean keepsUpEasily(int step) {
        var first = runSecond(schedule.firstSecondOf(step));
        var probe = measure(step, first, first + SETTLING_SECONDS + PROBE_SECONDS - 1, submitLateNanos.get());
        return probe.verdict() == SaturationStep.Verdict.KEEPS_UP && probe.writerBusyMax() < EASY_WRITER_BUSY;
    }

    private void offerSecond(long second, ExecutorService submitters) throws InterruptedException {
        var batches = new ArrayList<SpanBatch>();
        for (var partition : partitions) {
            batches.addAll(partition.ahead.take());
        }
        Collections.shuffle(batches, shuffling);
        var releaseStart = clock.nanoTimeAt(origin.plusSeconds(second));
        generatorLateNanos.put(second, Math.max(0, System.nanoTime() - releaseStart));
        for (var batchIndex = 0; batchIndex < batches.size(); batchIndex++) {
            var batch = batches.get(batchIndex);
            var due = releaseStart + SPREAD_NANOS * batchIndex / batches.size();
            parkUntil(due);
            offered.add(batch.size());
            submitters.execute(() -> submit(batch, due));
        }
    }

    // The run's second of a second of the schedule: the fill offered the ones before.
    private long runSecond(long second) {
        return fillSeconds + 1 + second;
    }

    private void submit(SpanBatch batch, long due) {
        submitLateNanos.accumulateAndGet(System.nanoTime() - due, Math::max);
        switch (hub.submit(batch)) {
            case Submission.Accepted(var refusal) -> {
                var refusedSpans = refusal.map(each -> each.spans()).orElse(0);
                accepted.add(batch.size() - refusedSpans);
                refused.add(refusedSpans);
            }
            case Submission.Busy ignored -> busy.add(batch.size());
        }
    }

    // The writer's idle time is the time it waited for a batch or a lock; the rest it ran, waited for a core, or stood in a collection's pause.
    // What the writer drained is read with the run's counters, when the epoch is received; the queued spans are the epoch's, as of its tick, which
    // can end well before then while the run accepts spans into the queues.
    private void sample(Thread writer) {
        var threads = ManagementFactory.getThreadMXBean();
        threads.setThreadContentionMonitoringEnabled(true);
        var process = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        var memory = ManagementFactory.getMemoryMXBean();
        var collectors = ManagementFactory.getGarbageCollectorMXBeans().stream().filter(collector -> !collector.getName().contains("Concurrent")).toList();
        try {
            for (var sequence = hub.epoch().sequence(); !Thread.currentThread().isInterrupted(); ) {
                var epoch = hub.awaitEpochAfter(sequence);
                var received = System.nanoTime();
                var drained = hub.queues().health().drained();
                sequence = epoch.sequence();
                var health = epoch.health();
                var at = epoch.at();
                var tickDue = Instant.ofEpochSecond(at.getEpochSecond()).plus(HubWriter.TICK_OFFSET);
                var writerInfo = threads.getThreadInfo(writer.threadId());
                samples.add(new Sample(
                    sequence,
                    at.getEpochSecond() - origin.getEpochSecond() - 1,
                    received,
                    offered.sum(),
                    accepted.sum(),
                    busy.sum(),
                    refused.sum(),
                    health.memory().queues().bytes() / Footprint.QUEUED_SPAN,
                    drained,
                    reason(health.applications(), HealthReason.SHED_UNRETAINED),
                    reason(health.applications(), HealthReason.SHED_UNJOINED),
                    threads.getThreadCpuTime(writer.threadId()),
                    (writerInfo.getWaitedTime() + writerInfo.getBlockedTime()) * 1_000_000,
                    process.getProcessCpuTime(),
                    Math.max(0, Duration.between(tickDue, at).toNanos()),
                    received - clock.nanoTimeAt(at),
                    collectors.stream().mapToLong(GarbageCollectorMXBean::getCollectionTime).sum(),
                    memory.getHeapMemoryUsage().getUsed(),
                    health.applications().stream().mapToLong(ApplicationHealth::retained).sum(),
                    health.applications().stream().mapToLong(application -> application.effectiveHorizon().toSeconds()).min().orElse(0),
                    health.effectiveHistory().toSeconds(),
                    health.memory().bytes()));
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    // As MonitorFrames: the frame of the latest epoch, written as the delta from the frame before, skipping the epochs published meanwhile.
    private void publish() {
        var mapper = new ObjectMapper();
        Frame previous = null;
        try {
            for (var sequence = hub.epoch().sequence(); !Thread.currentThread().isInterrupted(); ) {
                hub.awaitEpochAfter(sequence);
                var epoch = hub.epoch();
                var skipped = epoch.sequence() - sequence - 1;
                var started = System.nanoTime();
                var frame = new Frame(epoch.sequence(), FrameView.from(epoch.at(), epoch.statistics(), epoch.health(), OptionalLong.empty()));
                var json = previous == null ? mapper.writeValueAsString(frame.snapshot()) : mapper.writeValueAsString(frame.deltaFrom(previous, mapper));
                written(epoch, started, System.nanoTime(), json.getBytes(StandardCharsets.UTF_8).length, skipped);
                previous = frame;
                sequence = epoch.sequence();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void written(Epoch epoch, long startedNanos, long writtenNanos, long bytes, long skipped) {
        frames.put(epoch.sequence(), new FrameSample(epoch.at(), writtenNanos, writtenNanos - startedNanos, bytes, skipped));
    }

    private SaturationStep summarize(int index) {
        var step = measure(index, runSecond(schedule.firstSecondOf(index)), runSecond(schedule.lastSecondOf(index)), submitLateNanos.getAndSet(0));
        progress.report(output, step.line());
        return step;
    }

    // The samples of the step's seconds after its settling seconds, each against the sample before it.
    private SaturationStep measure(int index, long stepFirstSecond, long lastSecond, long submitLate) {
        var firstSecond = stepFirstSecond + SETTLING_SECONDS;
        var all = List.copyOf(samples);
        var intervals = new ArrayList<Interval>();
        for (var position = 1; position < all.size(); position++) {
            var sample = all.get(position);
            if (sample.second() >= firstSecond && sample.second() <= lastSecond) {
                intervals.add(new Interval(all.get(position - 1), sample, Optional.ofNullable(frames.get(sample.sequence()))));
            }
        }
        if (intervals.isEmpty()) {
            return SaturationStep.stalled(scenario.rates().get(index), stepFirstSecond, lastSecond, queueCapacity);
        }
        var first = intervals.getFirst().before();
        var last = intervals.getLast().after();
        var seconds = (last.receivedNanos() - first.receivedNanos()) / 1e9;
        var offeredSpans = last.offered() - first.offered();
        var processedSpans = last.drained() - first.drained();
        var generatorLate = generatorLateNanos.entrySet().stream().filter(each -> each.getKey() >= firstSecond && each.getKey() <= lastSecond)
            .mapToLong(Map.Entry::getValue).max().orElse(0);
        var frameSamples = intervals.stream().flatMap(interval -> interval.frame().stream()).toList();
        return new SaturationStep(
            scenario.rates().get(index),
            stepFirstSecond,
            lastSecond,
            offeredSpans / seconds,
            processedSpans / seconds,
            share(last.busy() - first.busy(), offeredSpans),
            last.refused() - first.refused(),
            share(last.shedUnretained() - first.shedUnretained(), processedSpans),
            share(last.shedUnjoined() - first.shedUnjoined(), processedSpans),
            intervals.stream().mapToLong(interval -> interval.after().queued()).max().orElse(0),
            last.queued(),
            queueCapacity,
            (double) (last.writerCpuNanos() - first.writerCpuNanos()) / (last.receivedNanos() - first.receivedNanos()),
            mean(intervals, Interval::writerBusy),
            intervals.stream().mapToDouble(Interval::writerBusy).max().orElse(0),
            (double) (last.processCpuNanos() - first.processCpuNanos()) / (last.receivedNanos() - first.receivedNanos()),
            percentile(intervals, interval -> interval.after().tickLateNanos(), 0.99) / 1e6,
            percentile(intervals, interval -> interval.after().publishLagNanos(), 0.99) / 1e6,
            percentile(frameSamples, FrameSample::nanos, 0.5) / 1e6,
            percentile(frameSamples, FrameSample::nanos, 1.0) / 1e6,
            percentile(frameSamples, FrameSample::bytes, 0.5) / 1024,
            frameSamples.stream().mapToLong(FrameSample::skipped).sum(),
            (double) (last.gcMillis() - first.gcMillis()) / seconds,
            intervals.stream().mapToLong(interval -> interval.after().heapBytes()).max().orElse(0) / MEGABYTE,
            generatorLate / 1e6,
            submitLate / 1e6,
            last.retained(),
            last.horizonSeconds(),
            last.historySeconds(),
            last.accountedBytes() / MEGABYTE,
            generatorLate > GENERATOR_LATE_NANOS,
            false);
    }

    private void write(List<SaturationStep> steps) throws IOException {
        var rows = new ArrayList<String>();
        rows.add("second,sequence,offered,accepted,busy,refused,queued,drained,shedUnretained,shedUnjoined,writerCpuMs,writerIdleMs,processCpuMs,tickLateMs,"
            + "publishLagMs,gcMs,heapMb,retained,horizonS,historyS,accountedMb,frameMs,frameBytes,framesSkipped,generatorLateMs,"
            + "tickMs,buildMs,waitMs,sendMs,lagMs,lagMaxMs,snapshots,unread,slowSendMs,slowLagMs,slowLagMaxMs,slowSnapshots,slowUnread");
        var reads = receipts.stream().collect(Collectors.groupingBy(FrameReceipt::epoch));
        for (var sample : samples) {
            var frame = Optional.ofNullable(frames.get(sample.sequence()));
            var delivery = frame.map(each -> deliveryColumns(sample, each, reads.getOrDefault(sample.sequence(), List.of())))
                .orElse(Collections.nCopies(DELIVERY_COLUMNS, -1L));
            rows.add(String.join(",", Stream.concat(Stream.of(sample.second(), sample.sequence(), sample.offered(), sample.accepted(), sample.busy(), sample.refused(),
                    sample.queued(), sample.drained(), sample.shedUnretained(), sample.shedUnjoined(), sample.writerCpuNanos() / 1_000_000, sample.writerIdleNanos() / 1_000_000,
                    sample.processCpuNanos() / 1_000_000, sample.tickLateNanos() / 1_000_000, sample.publishLagNanos() / 1_000_000, sample.gcMillis(),
                    sample.heapBytes() / MEGABYTE, sample.retained(), sample.horizonSeconds(), sample.historySeconds(), sample.accountedBytes() / MEGABYTE,
                    frame.map(each -> each.nanos() / 1_000_000).orElse(-1L), frame.map(FrameSample::bytes).orElse(-1L),
                    frame.map(FrameSample::skipped).orElse(-1L), generatorLateNanos.getOrDefault(sample.second(), -1_000_000L) / 1_000_000), delivery.stream())
                .map(String::valueOf).toList()));
        }
        Files.write(output.resolve(scenario.id() + ".csv"), rows);
        Files.write(output.resolve(scenario.id() + ".verdicts"), steps.stream().map(step -> step.rate() + "," + step.verdict()).toList());
        var deliveries = audience.any() ? steps.stream().map(this::delivery).toList() : List.<SaturationDelivery>of();
        if (audience.any()) {
            writeReceipts();
        }
        writeResult(steps, deliveries);
        var summary = new ArrayList<String>();
        summary.add("## " + scenario.name() + " (" + scenario.id() + ")");
        summary.add("");
        summary.add("%s, filled %d s; queues of %,d spans; run %s, recorded %s".formatted(scenario.description(), fillSeconds, queueCapacity,
            System.getProperty(RUN_PROPERTY), Instant.now()));
        summary.add("");
        summary.add(SaturationStep.HEADER);
        summary.add(SaturationStep.SEPARATOR);
        steps.forEach(step -> summary.add(step.row()));
        summary.add("");
        summary.add(SaturationStep.verdictOf(steps));
        summary.add("");
        if (audience.any()) {
            summary.add(("Frames read by %d clients as they come and %d reading %,d characters a second, which disconnected %d and %d times; lag from the end of "
                + "the second an epoch closes, send from the message written").formatted(audience.fast(), audience.slow(), audience.slowCharsPerSecond(),
                fastDisconnects, slowDisconnects));
            summary.add("");
            summary.add(SaturationDelivery.HEADER);
            summary.add(SaturationDelivery.SEPARATOR);
            deliveries.forEach(delivery -> summary.add(delivery.row()));
            summary.add("");
        }
        Files.write(output.resolve("summary.md"), summary, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        progress.report(output, SaturationStep.verdictOf(steps));
    }

    // The run's result for the saturation page: the run, the scenario's shape, its limits against the ladder's maximum, and every step with its
    // verdict.
    private void writeResult(List<SaturationStep> steps, List<SaturationDelivery> deliveries) throws IOException {
        var mapper = new ObjectMapper();
        var profile = scenario.profile();
        var keptUpTo = SaturationStep.keptUpTo(steps);
        var heldTo = SaturationStep.heldTo(steps);
        var failure = SaturationStep.firstFailure(steps);
        var stepRows = steps.stream().map(step -> {
            var row = new LinkedHashMap<String, Object>(mapper.convertValue(step, new TypeReference<Map<String, Object>>() {
            }));
            row.put("verdict", step.verdict().name());
            return row;
        }).toList();
        var result = new LinkedHashMap<String, Object>();
        result.put("run", System.getProperty(RUN_PROPERTY));
        result.put("machine", System.getProperty(MACHINE_PROPERTY, machine()));
        result.put("recordedAt", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
        result.put("durationS", progress.scenarioTook().toSeconds());
        result.put("scenario", scenario.id());
        result.put("name", scenario.name());
        result.put("sweep", scenario.sweep().key());
        result.put("description", scenario.description());
        result.put("applications", profile.applications());
        result.put("instances", profile.instances());
        result.put("typesPerApplication", profile.types());
        result.put("groupsPerApplication", profile.groups());
        result.put("memoryMb", profile.memory() / MEGABYTE);
        result.put("historyS", profile.history().toSeconds());
        result.put("queueSpans", queueCapacity);
        result.put("fillS", fillSeconds);
        result.put("stepS", scenario.stepSeconds());
        result.put("maxRate", SaturationScenario.MAX_RATE);
        result.put("keepsUp", keptUpTo.isPresent() ? keptUpTo.getAsInt() : null);
        result.put("holds", heldTo.isPresent() ? heldTo.getAsInt() : null);
        result.put("failsAt", failure.map(SaturationStep::rate).orElse(null));
        result.put("failsAs", failure.map(step -> step.verdict().name()).orElse(null));
        result.put("steps", stepRows);
        if (audience.any()) {
            var delivery = new LinkedHashMap<String, Object>();
            delivery.put("clients", audience.fast());
            delivery.put("slowClients", audience.slow());
            delivery.put("slowCharsPerSecond", audience.slowCharsPerSecond());
            delivery.put("disconnects", fastDisconnects);
            delivery.put("slowDisconnects", slowDisconnects);
            delivery.put("steps", deliveries);
            result.put("delivery", delivery);
        }
        Files.writeString(output.resolve(scenario.id() + ".json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result));
    }

    // The parts of an epoch's frame's lag up to the frame, which every client shares: the end of the second to the tick, the tick to the epoch's
    // publication as the sampler saw it, and the publication to the publisher's start on the frame. Then, for the fast clients and for the slow
    // ones, the part after the frame's writing, its send and read, for the client in the middle, so the parts add up to that client's lag; that lag
    // and the most lag of a client; and the clients that read the frame as a snapshot, and those that never read it, whether the hub skipped them or
    // the run ended first. -1 for what is missing, such as the figures of clients the run has none of.
    private List<Long> deliveryColumns(Sample sample, FrameSample frame, List<FrameReceipt> reads) {
        var end = secondEndNanos(frame);
        var tick = clock.nanoTimeAt(frame.at());
        var started = frame.writtenNanos() - frame.nanos();
        var build = Math.min(sample.publishLagNanos(), started - tick);
        var columns = new ArrayList<>(List.of(millis(tick - end), millis(build), millis(started - tick - build)));
        columns.addAll(clientColumns(audience.fast(), reads.stream().filter(read -> !read.slow()).toList(), frame, end));
        columns.addAll(clientColumns(audience.slow(), reads.stream().filter(FrameReceipt::slow).toList(), frame, end));
        return columns;
    }

    private static List<Long> clientColumns(int clients, List<FrameReceipt> reads, FrameSample frame, long end) {
        if (clients == 0) {
            return Collections.nCopies(5, -1L);
        }
        var received = reads.stream().mapToLong(FrameReceipt::receivedNanos).sorted().toArray();
        var middle = received.length == 0 ? -1 : received[(received.length - 1) / 2];
        return List.of(
            middle < 0 ? -1 : millis(middle - frame.writtenNanos()),
            middle < 0 ? -1 : millis(middle - end),
            received.length == 0 ? -1 : millis(received[received.length - 1] - end),
            reads.stream().filter(FrameReceipt::snapshot).count(),
            clients - reads.stream().mapToInt(FrameReceipt::client).distinct().count());
    }

    private static long millis(long nanos) {
        return nanos / 1_000_000;
    }

    // What the clients read of the frames of the step's seconds after it settled, the seconds measure summarizes.
    private SaturationDelivery delivery(SaturationStep step) {
        var firstSecond = step.firstSecond() + SETTLING_SECONDS;
        var published = new HashMap<Long, FrameSample>();
        for (var sample : samples) {
            var frame = frames.get(sample.sequence());
            if (frame != null && sample.second() >= firstSecond && sample.second() <= step.lastSecond()) {
                published.put(sample.sequence(), frame);
            }
        }
        var reads = receipts.stream().filter(receipt -> published.containsKey(receipt.epoch())).map(receipt -> {
            var frame = published.get(receipt.epoch());
            return new SaturationDelivery.Read(receipt.slow(), receipt.snapshot(), receipt.receivedNanos() - secondEndNanos(frame),
                receipt.receivedNanos() - frame.writtenNanos());
        }).toList();
        return SaturationDelivery.of(step.rate(), published.size(), audience, reads);
    }

    // Every frame a client read of an epoch ticked once the clock ran, with the second its epoch closes, as the CSV of the seconds counts them.
    private void writeReceipts() throws IOException {
        var rows = new ArrayList<String>();
        rows.add("second,epoch,client,slow,snapshot,chars,lagMs,sendMs");
        for (var receipt : receipts) {
            var frame = frames.get(receipt.epoch());
            if (frame == null || frame.at().getEpochSecond() - origin.getEpochSecond() <= fillSeconds) {
                continue;
            }
            rows.add(String.join(",", List.of(frame.at().getEpochSecond() - origin.getEpochSecond() - 1, receipt.epoch(), receipt.client(), receipt.slow(),
                    receipt.snapshot(), receipt.chars(), (receipt.receivedNanos() - secondEndNanos(frame)) / 1_000_000,
                    (receipt.receivedNanos() - frame.writtenNanos()) / 1_000_000)
                .stream().map(String::valueOf).toList()));
        }
        Files.write(output.resolve(scenario.id() + "-frames.csv"), rows);
    }

    // The end of the second an epoch closes; the writer ticks just after it.
    private long secondEndNanos(FrameSample frame) {
        return clock.nanoTimeAt(frame.at().truncatedTo(ChronoUnit.SECONDS));
    }

    // The short commit, marked when the working tree differs from it; unknown outside a git checkout.
    private static String commit() {
        try {
            var head = git("rev-parse", "--short", "HEAD");
            return git("status", "--porcelain", "--untracked-files=no").isBlank() ? head : head + " with changes";
        } catch (IOException failure) {
            return "unknown commit";
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return "unknown commit";
        }
    }

    private static String git(String... arguments) throws IOException, InterruptedException {
        var command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(arguments));
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        if (process.waitFor() != 0) {
            throw new IOException("git " + String.join(" ", arguments) + " failed: " + output);
        }
        return output;
    }

    private static String machine() {
        var system = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        var gigabyte = 1024 * MEGABYTE;
        var processor = Optional.ofNullable(System.getenv("PROCESSOR_IDENTIFIER")).orElse(System.getProperty("os.arch"));
        return "%s, %d threads, %d GB, %s, Java %s, %d GB heap".formatted(processor, Runtime.getRuntime().availableProcessors(),
            Math.round((double) system.getTotalMemorySize() / gigabyte), System.getProperty("os.name"), System.getProperty("java.version"),
            Runtime.getRuntime().maxMemory() / gigabyte);
    }

    private Thread writerThread() {
        return Thread.getAllStackTraces().keySet().stream()
            .filter(thread -> thread.getName().equals("smd-monitor-hub") && thread.isAlive())
            .max(Comparator.comparingLong(Thread::threadId))
            .orElseThrow(() -> new IllegalStateException("Saturation run " + scenario.id() + " found no running smd-monitor-hub writer thread"));
    }

    private static long reason(List<ApplicationHealth> applications, HealthReason reason) {
        return applications.stream().mapToLong(application -> application.count(reason)).sum();
    }

    private static void parkUntil(long nanoTime) {
        for (var remaining = nanoTime - System.nanoTime(); remaining > 0; remaining = nanoTime - System.nanoTime()) {
            LockSupport.parkNanos(remaining);
        }
    }

    private static double share(long part, long whole) {
        return whole <= 0 ? 0 : (double) part / whole;
    }

    private static <T> double mean(List<T> values, ToDoubleFunction<T> value) {
        return values.stream().mapToDouble(value).average().orElse(0);
    }

    static <T> double percentile(List<T> values, ToLongFunction<T> value, double quantile) {
        var sorted = values.stream().mapToLong(value).sorted().toArray();
        return sorted.length == 0 ? 0 : sorted[(int) Math.min(sorted.length - 1, Math.ceil(quantile * sorted.length) - 1)];
    }

    // One generator with its own clock, and the seconds it generated ahead of the one being offered. Only its own thread generates once the run
    // measures.
    private static final class Partition {

        private final LoadGenerator load;
        private final MutableClock clock;
        private final BlockingQueue<List<SpanBatch>> ahead = new ArrayBlockingQueue<>(GENERATED_AHEAD);

        private Partition(LoadGenerator load, MutableClock clock) {
            this.load = requireNonNull(load);
            this.clock = requireNonNull(clock);
        }

        private List<SpanBatch> generate() {
            clock.advance(Duration.ofSeconds(1));
            return load.export();
        }

        private void generateAhead() {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    ahead.put(generate());
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // What the run observed when the epoch was published: its own counters, the hub's health, and the JVM's. The second is the one whose batches
    // the epoch's tick followed.
    private record Sample(long sequence, long second, long receivedNanos, long offered, long accepted, long busy, long refused, long queued,
                          long drained, long shedUnretained, long shedUnjoined, long writerCpuNanos, long writerIdleNanos, long processCpuNanos,
                          long tickLateNanos, long publishLagNanos, long gcMillis, long heapBytes, long retained, long horizonSeconds, long historySeconds,
                          long accountedBytes) {
    }

    // The frame of an epoch, ticked at the given instant: when the publisher had written it, how long that took, its size, and the epochs skipped before it.
    private record FrameSample(Instant at, long writtenNanos, long nanos, long bytes, long skipped) {

        private FrameSample {
            requireNonNull(at);
        }
    }

    private record Interval(Sample before, Sample after, Optional<FrameSample> frame) {

        private Interval {
            requireNonNull(before);
            requireNonNull(after);
            requireNonNull(frame);
        }

        private double writerBusy() {
            var wall = after.receivedNanos() - before.receivedNanos();
            var idle = after.writerIdleNanos() - before.writerIdleNanos();
            return wall <= 0 ? 0 : Math.clamp(1 - (double) idle / wall, 0, 1);
        }
    }
}
