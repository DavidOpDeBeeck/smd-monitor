package app.dodb.smd.monitor.hub;

import app.dodb.smd.monitor.hub.budget.HeapCheck;
import app.dodb.smd.monitor.hub.budget.MemoryBudget;
import app.dodb.smd.monitor.hub.intake.Admission;
import app.dodb.smd.monitor.hub.intake.IntakeQueues;
import app.dodb.smd.monitor.hub.intake.SpanBatch;
import app.dodb.smd.monitor.hub.intake.Verdict;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

// The monitor hub as it runs: admission on the threads that receive spans, a bounded intake queue per application, and a writer thread that
// owns the hub and publishes an immutable epoch every second, which the frame and the queries read. Ingest never waits for a reader.
public final class MonitorHub implements AutoCloseable {

    // How long an exporter waits before it sends a batch again that the hub was too busy to take; within the backoff of OpenTelemetry's exporters.
    public static final Duration RETRY_AFTER = Duration.ofSeconds(2);
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(5);
    private static final Logger LOGGER = LoggerFactory.getLogger(MonitorHub.class);

    private final Clock clock;
    private final Admission admission;
    private final IntakeQueues queues;
    private final Epochs epochs;
    private final HubWriter writer;
    private final Thread thread;

    // Fails when the memory budget takes more than 75% of the JVM's maximum heap, and warns above 25%.
    public MonitorHub(HubConfig config) {
        this(config, Clock.systemUTC(), MemoryBudget.of(config.memory()), Runtime.getRuntime().maxMemory());
    }

    MonitorHub(HubConfig config, Clock clock, MemoryBudget budget, long maxHeap) {
        switch (MemoryBudget.checkHeap(budget.total(), maxHeap)) {
            case HeapCheck.Exceeds(var message) -> throw new IllegalStateException(message);
            case HeapCheck.Warns(var message) -> LOGGER.warn(message);
            case HeapCheck.Fits ignored -> {
            }
        }
        this.clock = requireNonNull(clock);
        var hub = new Hub(config, budget);
        this.admission = new Admission(config);
        this.queues = new IntakeQueues(budget.queues());
        this.epochs = new Epochs(hub.tick(clock.instant(), Map.of(), admission.refused(), queues.health()));
        this.writer = new HubWriter(admission, queues, hub, epochs, clock);
        this.thread = Thread.ofPlatform().name("smd-monitor-hub").daemon().unstarted(writer);
    }

    public void start() {
        thread.start();
    }

    // Admits the batch on the calling thread and queues what admission let through for the writer, unless its application's queue is full.
    public Submission submit(SpanBatch batch) {
        return switch (admission.admit(batch, clock.instant())) {
            case Verdict.Refused(var refusal) -> new Submission.Accepted(Optional.of(refusal));
            case Verdict.Admitted(var admitted, var refusal) -> admitted.size() == 0 || queues.offer(admitted)
                ? new Submission.Accepted(refusal)
                : new Submission.Busy(batch.application(), RETRY_AFTER);
        };
    }

    public Epoch epoch() {
        return epochs.latest();
    }

    // The first epoch after the given one, waiting for the writer to publish it.
    public Epoch awaitEpochAfter(long sequence) throws InterruptedException {
        return epochs.awaitAfter(sequence);
    }

    @Override
    public void close() {
        if (thread.getState() == Thread.State.NEW) {
            return;
        }
        thread.interrupt();
        try {
            thread.join(CLOSE_TIMEOUT);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    Admission admission() {
        return admission;
    }

    HubWriter writer() {
        return writer;
    }

    IntakeQueues queues() {
        return queues;
    }
}
