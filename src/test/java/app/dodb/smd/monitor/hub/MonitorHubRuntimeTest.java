package app.dodb.smd.monitor.hub;

import app.dodb.smd.monitor.hub.RecordingInstance.MutableClock;
import app.dodb.smd.monitor.hub.budget.Budgets;
import app.dodb.smd.monitor.hub.budget.MemoryBudget;
import app.dodb.smd.monitor.hub.intake.QueuedBytes;
import app.dodb.smd.monitor.hub.span.MessageSpan;
import app.dodb.smd.monitor.hub.span.Outcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;

import static app.dodb.smd.monitor.hub.RecordingInstance.ASSIGN;
import static app.dodb.smd.monitor.hub.RecordingInstance.BILLING;
import static app.dodb.smd.monitor.hub.RecordingInstance.OPEN;
import static app.dodb.smd.monitor.hub.RecordingInstance.STARTED_AT;
import static app.dodb.smd.monitor.hub.RecordingInstance.TICKETS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

// The hub as it runs: admission on the submitting thread, an intake queue per application, and the writer thread publishing epochs.
class MonitorHubRuntimeTest {

    private static final Duration EPOCHS = Duration.ofSeconds(5);
    private static final TapeQuery TAPE = new TapeQuery(InvocationFilter.EVERY_INVOCATION, Duration.ZERO, Optional.empty(), TapeQuery.MAX_LIMIT);

    private final RecordingInstance tickets = new RecordingInstance(TICKETS, "tickets-1");
    private final RecordingInstance billing = new RecordingInstance(BILLING, "billing-1");
    private final MonitorHub hub = new MonitorHub(HubConfig.withDefaults().build(), Clock.systemUTC(), Budgets.generous(), Long.MAX_VALUE);
    private final CountDownLatch release = new CountDownLatch(1);

    @AfterEach
    void close() {
        release.countDown();
        hub.close();
    }

    @Test
    void submit_whileAReaderIsBlockedHoldingTheHubAndItsEpochAndAFrameIsBlocked_isReceivedAndPublished() throws InterruptedException {
        hub.start();
        var read = hub.epoch();
        var reading = new CountDownLatch(1);
        // Holds every lock a reader could hold, the hub's and the epoch's, as a reader stuck inside a query would; none of them is the writer's.
        var query = Thread.ofPlatform().start(() -> {
            synchronized (hub) {
                synchronized (read) {
                    read.tape(TAPE);
                    reading.countDown();
                    awaitRelease();
                }
            }
        });
        reading.await();
        var framing = new CountDownLatch(1);
        var frame = Thread.ofPlatform().start(() -> {
            try {
                hub.awaitEpochAfter(read.sequence());
                framing.countDown();
                awaitRelease();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        await().atMost(EPOCHS).until(() -> framing.getCount() == 0);
        var open = tickets.dispatch(OPEN);

        var submission = hub.submit(tickets.batch(open));

        assertThat(submission).isEqualTo(new Submission.Accepted(Optional.empty()));
        await().atMost(EPOCHS).untilAsserted(() -> assertThat(retained(hub.epoch())).contains(open));
        assertThat(query.isAlive()).isTrue();
        assertThat(frame.isAlive()).isTrue();
        release.countDown();
        query.join();
        frame.join();
    }

    @Test
    void new_budgetAboveThreeQuartersOfTheMaximumHeap_failsToStart() {
        var budget = MemoryBudget.of(100L * 1024 * 1024);

        assertThatThrownBy(() -> new MonitorHub(HubConfig.withDefaults().build(), Clock.systemUTC(), budget, 128L * 1024 * 1024))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("smd.monitor.hub.memory", "75%", String.valueOf(budget.total()));
    }

    @Test
    void submit_applicationQueueFull_answersBusyForThatApplicationAndAcceptsAnother() {
        var hub = new MonitorHub(HubConfig.withDefaults().build(), Clock.systemUTC(), Budgets.queuing(2 * oneSpan()), Long.MAX_VALUE);
        hub.submit(tickets.batch(tickets.dispatch(OPEN)));

        var busy = hub.submit(tickets.batch(tickets.dispatch(OPEN), tickets.dispatch(OPEN)));
        var other = hub.submit(billing.batch(billing.dispatch(ASSIGN)));

        assertThat(busy).isEqualTo(new Submission.Busy(TICKETS, MonitorHub.RETRY_AFTER));
        assertThat(other).isEqualTo(new Submission.Accepted(Optional.empty()));
    }

    @Test
    void submit_queueTheWriterDrained_acceptsTheApplicationAgain() throws InterruptedException {
        var hub = new MonitorHub(HubConfig.withDefaults().build(), Clock.systemUTC(), Budgets.queuing(2 * oneSpan()), Long.MAX_VALUE);
        hub.submit(tickets.batch(tickets.dispatch(OPEN), tickets.dispatch(OPEN)));
        var busy = hub.submit(tickets.batch(tickets.dispatch(OPEN)));
        hub.writer().receiveQueued();

        var accepted = hub.submit(tickets.batch(tickets.dispatch(OPEN)));

        assertThat(busy).isInstanceOf(Submission.Busy.class);
        assertThat(accepted).isEqualTo(new Submission.Accepted(Optional.empty()));
    }

    @Test
    void submit_refusedByAdmission_answersTheRefusalWithoutQueueingAnything() {
        var hub = new MonitorHub(HubConfig.withDefaults().maxApplications(1).build(), Clock.systemUTC(), Budgets.generous(), Long.MAX_VALUE);
        hub.submit(tickets.batch(tickets.dispatch(OPEN)));

        var refused = hub.submit(billing.batch(billing.dispatch(ASSIGN), billing.dispatch(ASSIGN)));

        assertThat(refused).isInstanceOfSatisfying(Submission.Accepted.class, answer -> assertThat(answer.refusal()).hasValueSatisfying(refusal -> {
            assertThat(refusal.spans()).isEqualTo(2);
            assertThat(refusal.reason()).contains("already monitors 1 applications", BILLING.value());
        }));
        assertThat(hub.queues().health().queued()).isEqualTo(1);
    }

    @Test
    void submit_batchTheHubFailsOn_dropsItAndKeepsReceiving() {
        hub.start();
        var failing = RecordingInstance.with(tickets.dispatch(OPEN), STARTED_AT, new Outcome.Completed(Duration.ofSeconds(Long.MAX_VALUE)));
        var open = billing.dispatch(OPEN);

        hub.submit(tickets.batch(failing));
        hub.submit(billing.batch(open));

        await().atMost(EPOCHS).untilAsserted(() -> assertThat(retained(hub.epoch())).containsExactly(open));
        assertThat(hub.writer().failedBatches()).isEqualTo(1);
    }

    @Test
    void awaitEpochAfter_startedHub_answersTheNextEpochTheWriterPublishes() throws InterruptedException {
        hub.start();
        var first = hub.awaitEpochAfter(hub.epoch().sequence());

        var next = hub.awaitEpochAfter(first.sequence());

        assertThat(next.sequence()).isEqualTo(first.sequence() + 1);
        assertThat(next.at()).isAfter(first.at());
        assertThat(hub.epoch().sequence()).isGreaterThanOrEqualTo(next.sequence());
    }

    @Test
    void tick_applicationRetiredByAdmission_dropsItsStateAndItsQueue() throws InterruptedException {
        var clock = new MutableClock();
        var hub = new MonitorHub(HubConfig.withDefaults().maxApplications(1).build(), clock, Budgets.queuing(2 * oneSpan()), Long.MAX_VALUE);
        hub.submit(tickets.batch(tickets.dispatch(OPEN)));
        hub.writer().receiveQueued();
        clock.advance(HubConfig.DEFAULT_RETIRE_AFTER);

        var epoch = hub.writer().tick(clock.instant());

        assertThat(epoch.statistics().applications()).isEmpty();
        assertThat(retained(epoch)).isEmpty();
        assertThat(hub.submit(billing.batch(billing.dispatch(ASSIGN), billing.dispatch(ASSIGN)))).isEqualTo(new Submission.Accepted(Optional.empty()));
    }

    // The room a batch of one dispatch takes in the queues.
    private long oneSpan() {
        return QueuedBytes.of(tickets.batch(tickets.dispatch(OPEN)));
    }

    private void awaitRelease() {
        try {
            release.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static List<MessageSpan> retained(Epoch epoch) {
        return epoch.tape(TAPE).invocations().stream().map(invocation -> invocation.invocation().span()).toList();
    }
}
