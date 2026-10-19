package app.dodb.smd.monitor.hub.intake;

import app.dodb.smd.api.event.delivery.EventDelivery;
import app.dodb.smd.monitor.hub.span.ApplicationName;
import app.dodb.smd.monitor.hub.span.InstanceName;
import app.dodb.smd.monitor.hub.span.MessageKind;
import app.dodb.smd.monitor.hub.span.MessageType;
import app.dodb.smd.monitor.hub.span.SessionId;
import app.dodb.smd.monitor.hub.span.SinkSend;
import app.dodb.smd.monitor.hub.span.SpanRef;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class IntakeQueuesTest {

    private static final ApplicationName TICKETS = new ApplicationName("ticket-service");
    private static final ApplicationName BILLING = new ApplicationName("billing-service");
    private static final ApplicationName SHIPPING = new ApplicationName("shipping-service");
    private static final MessageType OPENED = new MessageType(MessageKind.EVENT, "TicketOpened");

    private final AtomicLong ids = new AtomicLong();

    @Test
    void drain_batchesOfTwoApplications_takesThemRoundRobinAndEachInOrder() throws InterruptedException {
        var queues = new IntakeQueues(100);
        var tickets = List.of(batch(TICKETS, 1), batch(TICKETS, 1), batch(TICKETS, 1));
        var billing = List.of(batch(BILLING, 1), batch(BILLING, 1));
        tickets.forEach(queues::offer);
        billing.forEach(queues::offer);

        var drained = drainAll(queues);

        assertThat(drained).containsExactly(tickets.get(0), billing.get(0), tickets.get(1), billing.get(1), tickets.get(2));
    }

    @Test
    void offer_burstBeyondTheReservedRoomWhileTheOthersAreQuiet_isTakenFromThePool() {
        var queues = new IntakeQueues(40);
        queues.offer(batch(BILLING, 1));
        queues.offer(batch(TICKETS, 10));

        var borrowing = queues.offer(batch(TICKETS, 5));
        var halfThePool = queues.offer(batch(TICKETS, 5));

        assertThat(borrowing).isTrue();
        assertThat(halfThePool).isTrue();
    }

    @Test
    void offer_applicationBorrowingHalfThePool_isRefusedWhileTheOthersStillBorrow() {
        var queues = new IntakeQueues(40);
        queues.offer(batch(BILLING, 1));
        queues.offer(batch(TICKETS, 10));
        queues.offer(batch(TICKETS, 10));

        var beyondHalf = queues.offer(batch(TICKETS, 1));
        var other = queues.offer(batch(BILLING, 19));

        assertThat(beyondHalf).isFalse();
        assertThat(other).isTrue();
        assertThat(queues.health().full()).containsOnlyKeys(TICKETS);
    }

    @Test
    void offer_poolSpent_refusesBorrowingButTakesBatchesWithinTheReservedRoom() {
        var queues = new IntakeQueues(60);
        queues.offer(batch(TICKETS, 10));
        queues.offer(batch(BILLING, 10));
        queues.offer(batch(SHIPPING, 1));
        queues.offer(batch(TICKETS, 15));
        queues.offer(batch(BILLING, 15));

        var withinReservedRoom = queues.offer(batch(SHIPPING, 9));
        var borrowing = queues.offer(batch(SHIPPING, 1));

        assertThat(withinReservedRoom).isTrue();
        assertThat(borrowing).isFalse();
        assertThat(queues.health().full()).containsEntry(SHIPPING, 1L);
    }

    @Test
    void offer_applicationsBorrowingUntilRefused_holdAtMostTheCapacity() {
        var queues = new IntakeQueues(100);

        for (var round = 0; round < 50; round++) {
            for (var application : List.of(TICKETS, BILLING, SHIPPING)) {
                queues.offer(batch(application, 3));
            }
        }

        assertThat(queues.health().queued()).isLessThanOrEqualTo(100);
        assertThat(queues.health().full()).containsOnlyKeys(TICKETS, BILLING, SHIPPING);
    }

    @Test
    void offer_batchLargerThanTheShareIntoAnEmptyQueue_isTaken() {
        var queues = new IntakeQueues(10);

        var taken = queues.offer(batch(TICKETS, 25));

        assertThat(taken).isTrue();
        assertThat(queues.offer(batch(TICKETS, 1))).isFalse();
    }

    @Test
    void offer_drainedQueue_takesBatchesAgain() throws InterruptedException {
        var queues = new IntakeQueues(10);
        queues.offer(batch(TICKETS, 10));
        drainAll(queues);

        var taken = queues.offer(batch(TICKETS, 10));

        assertThat(taken).isTrue();
    }

    @Test
    void drain_applicationBorrowingMore_shedsMoreAsItBorrows() throws InterruptedException {
        var queues = new IntakeQueues(40);
        IntStream.range(0, 30).forEach(ignored -> queues.offer(batch(TICKETS, 1)));

        var sheddings = new ArrayList<Shedding>();
        for (var drained = queues.drain(Duration.ZERO); drained.isPresent(); drained = queues.drain(Duration.ZERO)) {
            sheddings.add(drained.get().shedding());
        }

        assertThat(sheddings.subList(0, 6)).containsExactly(Shedding.UNJOINED, Shedding.UNJOINED, Shedding.INTERESTING_ONLY, Shedding.INTERESTING_ONLY,
            Shedding.INTERESTING_ONLY, Shedding.NONE);
        assertThat(sheddings.subList(6, 30)).containsOnly(Shedding.NONE);
    }

    @Test
    void drain_poolFillingUp_shedsTheBorrowingApplications() throws InterruptedException {
        var queues = new IntakeQueues(120);
        for (var application : List.of(TICKETS, BILLING, SHIPPING)) {
            queues.offer(batch(application, 1));
            queues.offer(batch(application, 20));
            queues.offer(batch(application, 12));
        }

        var drained = queues.drain(Duration.ZERO);

        assertThat(drained).hasValueSatisfying(batch -> assertThat(batch.shedding()).isEqualTo(Shedding.INTERESTING_ONLY));
    }

    @Test
    void drain_fewBatchesBehindWhileThePoolIsMostlyFree_isNotShed() throws InterruptedException {
        var queues = new IntakeQueues(200);
        queues.offer(batch(BILLING, 1));
        queues.offer(batch(TICKETS, 1));
        queues.offer(batch(TICKETS, 30));
        queues.offer(batch(TICKETS, 30));
        queues.drain(Duration.ZERO);

        var drained = queues.drain(Duration.ZERO);

        assertThat(drained).hasValueSatisfying(batch -> assertThat(batch.shedding()).isEqualTo(Shedding.NONE));
    }

    @Test
    void drain_loneBatchBeyondTheReservedRoom_isNotShed() throws InterruptedException {
        var queues = new IntakeQueues(10);
        queues.offer(batch(TICKETS, 8));

        var drained = queues.drain(Duration.ZERO);

        assertThat(drained).hasValueSatisfying(batch -> assertThat(batch.shedding()).isEqualTo(Shedding.NONE));
    }

    @Test
    void retire_emptyQueueOfARetiredApplication_returnsItsShareToTheOthers() throws InterruptedException {
        var queues = new IntakeQueues(20);
        queues.offer(batch(BILLING, 1));
        queues.offer(batch(TICKETS, 10));
        assertThat(queues.offer(batch(TICKETS, 1))).isFalse();
        assertThat(queues.drain(Duration.ZERO)).hasValueSatisfying(drained -> assertThat(drained.batch().application()).isEqualTo(BILLING));

        queues.retire(BILLING);

        assertThat(queues.offer(batch(TICKETS, 5))).isTrue();
    }

    @Test
    void health_batchesDrainedAndOfferedSince_countsTheDrainedSpansSinceTheStart() throws InterruptedException {
        var queues = new IntakeQueues(100);
        queues.offer(batch(TICKETS, 3));
        queues.offer(batch(BILLING, 2));
        drainAll(queues);
        queues.offer(batch(TICKETS, 4));
        queues.offer(batch(TICKETS, 5));
        queues.drain(Duration.ZERO);

        var health = queues.health();

        assertThat(health.drained()).isEqualTo(9);
        assertThat(health.queued()).isEqualTo(5);
    }

    @Test
    void drain_nothingQueued_returnsEmptyOnceTheWaitEnds() throws InterruptedException {
        var queues = new IntakeQueues(10);

        var drained = queues.drain(Duration.ofMillis(1));

        assertThat(drained).isEmpty();
    }

    private static List<SpanBatch> drainAll(IntakeQueues queues) throws InterruptedException {
        var drained = new ArrayList<SpanBatch>();
        for (var next = queues.drain(Duration.ZERO); next.isPresent(); next = queues.drain(Duration.ZERO)) {
            drained.add(next.get().batch());
        }
        return drained;
    }

    // A batch of sink sends, the smallest records a batch holds.
    private SpanBatch batch(ApplicationName application, int sends) {
        return new SpanBatch(application, new InstanceName(application.value() + "-1"), new SessionId("session"), List.of(),
            IntStream.range(0, sends).mapToObj(ignored -> send()).toList());
    }

    private SinkSend send() {
        var id = ids.incrementAndGet();
        return new SinkSend(SpanRef.of(HexFormat.of().toHexDigits(0L) + HexFormat.of().toHexDigits(id), HexFormat.of().toHexDigits(id)), OPENED,
            EventDelivery.SYNC, Instant.parse("2026-01-01T12:00:00Z"));
    }
}
