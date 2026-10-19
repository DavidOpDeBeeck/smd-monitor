package app.dodb.smd.monitor.hub.intake;

import app.dodb.smd.monitor.hub.span.ApplicationName;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import static java.util.Objects.requireNonNull;

// One bounded queue of admitted batches per application, which the writer drains round-robin, so one application can neither starve another's
// ingest nor fill the others' room (TR9). The applications share the capacity equally; an empty queue still takes one batch, so an application
// whose batch exceeds its share can report, and a queue within its share takes one more batch beyond it while all the queues keep within the
// capacity, so the batches an application's instances export together are not refused while the hub has room. An application can thus take at
// most one batch more than its share. How full a queue is behind the batch the writer takes sets that batch's shedding, so a batch alone in its
// queue is never shed, whatever its size against the share. The memory budget sets the capacity (P1).
public final class IntakeQueues {

    private static final double INTERESTING_ONLY_FILL = 0.5;
    private static final double UNJOINED_FILL = 0.8;

    private final int capacity;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition offered = lock.newCondition();
    // Guarded by the lock: the queue of every application that offered a batch and did not retire, and those holding batches in drain order.
    private final Map<ApplicationName, Queue> queues = new HashMap<>();
    private final ArrayDeque<Queue> ready = new ArrayDeque<>();
    private final Map<ApplicationName, Long> full = new HashMap<>();
    private long queued;
    private long drained;

    public IntakeQueues(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("IntakeQueues require a capacity of at least 1 span; got " + capacity);
        }
        this.capacity = capacity;
    }

    // False when the application's queue is full: it holds batches, this one would take it beyond the application's share, and either the queue
    // is beyond its share already or the queues together would exceed the capacity.
    public boolean offer(SpanBatch batch) {
        lock.lock();
        try {
            var queue = queues.computeIfAbsent(batch.application(), ignored -> new Queue());
            var beyondShare = queue.spans + batch.size() > share();
            var mayExceedShare = queue.spans <= share() && queued + batch.size() <= capacity;
            if (!queue.batches.isEmpty() && beyondShare && !mayExceedShare) {
                full.merge(batch.application(), (long) batch.size(), Long::sum);
                return false;
            }
            if (queue.batches.isEmpty()) {
                ready.addLast(queue);
            }
            queue.batches.addLast(batch);
            queue.spans += batch.size();
            queued += batch.size();
            offered.signal();
            return true;
        } finally {
            lock.unlock();
        }
    }

    // The next batch in round-robin order over the applications, waiting at most the given time for one.
    public Optional<Drained> drain(Duration wait) throws InterruptedException {
        lock.lock();
        try {
            var remaining = wait.toNanos();
            while (ready.isEmpty()) {
                if (remaining <= 0) {
                    return Optional.empty();
                }
                remaining = offered.awaitNanos(remaining);
            }
            var queue = ready.pollFirst();
            var batch = queue.batches.pollFirst();
            queue.spans -= batch.size();
            queued -= batch.size();
            drained += batch.size();
            var shedding = shedding(queue);
            if (!queue.batches.isEmpty()) {
                ready.addLast(queue);
            }
            return Optional.of(new Drained(batch, shedding));
        } finally {
            lock.unlock();
        }
    }

    // Forgets the queue of an application admission retired, returning its share to the others, unless it was admitted again and holds batches.
    public void retire(ApplicationName application) {
        lock.lock();
        try {
            var queue = queues.computeIfPresent(application, (ignored, kept) -> kept.batches.isEmpty() ? null : kept);
            if (queue == null) {
                full.remove(application);
            }
        } finally {
            lock.unlock();
        }
    }

    public QueueHealth health() {
        lock.lock();
        try {
            return new QueueHealth(queued, drained, Map.copyOf(full));
        } finally {
            lock.unlock();
        }
    }

    private int share() {
        return capacity / queues.size();
    }

    private Shedding shedding(Queue queue) {
        var fill = (double) queue.spans / share();
        if (fill >= UNJOINED_FILL) {
            return Shedding.UNJOINED;
        }
        return fill >= INTERESTING_ONLY_FILL ? Shedding.INTERESTING_ONLY : Shedding.NONE;
    }

    public record Drained(SpanBatch batch, Shedding shedding) {

        public Drained {
            requireNonNull(batch);
            requireNonNull(shedding);
        }
    }

    private static final class Queue {

        private final ArrayDeque<SpanBatch> batches = new ArrayDeque<>();
        private long spans;
    }
}
