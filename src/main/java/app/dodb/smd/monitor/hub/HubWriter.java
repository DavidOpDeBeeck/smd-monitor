package app.dodb.smd.monitor.hub;

import app.dodb.smd.monitor.hub.intake.Admission;
import app.dodb.smd.monitor.hub.intake.IntakeQueues;
import app.dodb.smd.monitor.hub.intake.Shedding;
import app.dodb.smd.monitor.hub.intake.SpanBatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

import static java.util.Objects.requireNonNull;

// The only thread that touches the hub. It drains the intake queues round-robin, and ticks 50 ms after each second: it drops the applications
// admission retired and publishes the hub's next epoch. A batch that fails is dropped and counted, the first failure in a row is logged, and the
// writer keeps running; the spans of that batch received before the failure stay received.
final class HubWriter implements Runnable {

    static final Duration TICK_OFFSET = Duration.ofMillis(50);
    private static final Logger LOGGER = LoggerFactory.getLogger(HubWriter.class);

    private final Admission admission;
    private final IntakeQueues queues;
    private final Hub hub;
    private final Epochs epochs;
    private final Clock clock;
    private final AtomicLong failedBatches = new AtomicLong();
    // Only the writer thread reads and writes these.
    private boolean receiveFailing;
    private boolean tickFailing;

    HubWriter(Admission admission, IntakeQueues queues, Hub hub, Epochs epochs, Clock clock) {
        this.admission = requireNonNull(admission);
        this.queues = requireNonNull(queues);
        this.hub = requireNonNull(hub);
        this.epochs = requireNonNull(epochs);
        this.clock = requireNonNull(clock);
    }

    @Override
    public void run() {
        var nextTick = nextTick(clock.instant());
        try {
            while (!Thread.currentThread().isInterrupted()) {
                var now = clock.instant();
                if (now.isBefore(nextTick)) {
                    var drained = queues.drain(Duration.between(now, nextTick));
                    if (drained.isPresent()) {
                        receive(drained.get().batch(), drained.get().shedding());
                    }
                    continue;
                }
                tickSafely(now);
                // From the end of the tick, so a tick that overran its second still leaves the writer time to drain.
                nextTick = nextTick(clock.instant());
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    // Receives what the queues hold now, without waiting for more.
    void receiveQueued() throws InterruptedException {
        for (var drained = queues.drain(Duration.ZERO); drained.isPresent(); drained = queues.drain(Duration.ZERO)) {
            receive(drained.get().batch(), drained.get().shedding());
        }
    }

    void receive(SpanBatch batch, Shedding shedding) {
        try {
            hub.receive(batch, shedding, clock.instant());
            if (receiveFailing) {
                receiveFailing = false;
                LOGGER.info("The SMD monitor hub receives batches again; it dropped {} failing batches since it started", failedBatches.get());
            }
        } catch (RuntimeException failure) {
            failedBatches.incrementAndGet();
            hub.failed(batch, clock.instant());
            if (!receiveFailing) {
                receiveFailing = true;
                LOGGER.warn("The SMD monitor hub dropped a batch of {} spans of instance '{}' of application '{}' that failed; it drops each failing batch "
                    + "and keeps receiving", batch.size(), batch.instance(), batch.application(), failure);
            }
        }
    }

    Epoch tick(Instant now) {
        for (var application : admission.retire(now)) {
            hub.retire(application);
            queues.retire(application);
            LOGGER.info("The SMD monitor hub retired application '{}', none of whose instances sent spans for its retire-after", application);
        }
        var epoch = hub.tick(now, admission.instances(now), admission.refused(), queues.health());
        epochs.publish(epoch);
        return epoch;
    }

    long failedBatches() {
        return failedBatches.get();
    }

    private void tickSafely(Instant now) {
        try {
            tick(now);
            if (tickFailing) {
                tickFailing = false;
                LOGGER.info("The SMD monitor hub publishes epochs again");
            }
        } catch (RuntimeException failure) {
            if (!tickFailing) {
                tickFailing = true;
                LOGGER.error("The SMD monitor hub failed to publish an epoch; frames and queries show the last one it published", failure);
            }
        }
    }

    private static Instant nextTick(Instant now) {
        var tick = Instant.ofEpochSecond(now.getEpochSecond()).plus(TICK_OFFSET);
        return tick.isAfter(now) ? tick : tick.plusSeconds(1);
    }
}
