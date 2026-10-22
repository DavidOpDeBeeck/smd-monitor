package app.dodb.smd.monitor.hub.intake;

import app.dodb.smd.monitor.hub.span.ApplicationName;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.ToLongFunction;

import static java.util.Objects.requireNonNull;

// One bounded queue of admitted batches per application, which the writer drains round-robin, so one application can neither starve another's
// ingest nor fill the others' room (TR9). Each application has half an equal share of the capacity reserved; the rest is a pool any application
// borrows from beyond its reserved room, up to half the pool, so the batches an application's instances export together, or that arrive at once
// after a pause, are taken while the hub has room, and a flooding application still leaves the others half the pool. The queues thus hold at most
// the capacity, but that an empty queue still takes one batch, so an application whose batch exceeds its reserved room can report. A batch is
// shed only beyond the reserved room: by how full the pool is, or how much of its half the batch's application borrows, whichever is fuller. The
// memory budget sets the capacity in bytes (P1): a batch waits packed (QueuedBatch) and takes the room of the heap it holds.
public final class IntakeQueues {

    private static final double INTERESTING_ONLY_FILL = 0.5;
    private static final double UNJOINED_FILL = 0.8;

    private final long capacity;
    // The room a batch takes of the capacity.
    private final ToLongFunction<QueuedBatch> room;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition offered = lock.newCondition();
    // Guarded by the lock: the queue of every application that offered a batch and did not retire, and those holding batches in drain order.
    private final Map<ApplicationName, Queue> queues = new HashMap<>();
    private final ArrayDeque<Queue> ready = new ArrayDeque<>();
    private final Map<ApplicationName, Long> full = new HashMap<>();
    private long queued;
    private long queuedRoom;
    private long drained;
    // Guarded by the lock, and set again whenever an application's queue is added or forgotten: the room reserved for each application, the pool
    // beyond the reserved rooms, and what the queues hold of the pool.
    private long reserved;
    private long pool;
    private long borrowed;

    // The capacity is the bytes the queued batches may hold.
    public IntakeQueues(long capacity) {
        this(capacity, QueuedBatch::bytes);
    }

    IntakeQueues(long capacity, ToLongFunction<QueuedBatch> room) {
        if (capacity < 1) {
            throw new IllegalArgumentException("IntakeQueues require a capacity of at least 1 byte; got " + capacity);
        }
        this.capacity = capacity;
        this.room = requireNonNull(room);
    }

    // False when the application's queue is full: it holds batches, and this one would borrow beyond the pool or beyond half of it. The calling
    // thread packs the batch before it takes the lock.
    public boolean offer(SpanBatch batch) {
        var packed = QueuedBatch.of(batch);
        var taking = room.applyAsLong(packed);
        lock.lock();
        try {
            var queue = queues.get(batch.application());
            if (queue == null) {
                queue = new Queue();
                queues.put(batch.application(), queue);
                share();
            }
            var borrowing = borrowedBy(queue.room + taking) - borrowedBy(queue.room);
            var beyondPool = borrowed + borrowing > pool || borrowedBy(queue.room + taking) > halfPool();
            if (!queue.batches.isEmpty() && borrowing > 0 && beyondPool) {
                full.merge(batch.application(), (long) batch.size(), Long::sum);
                return false;
            }
            if (queue.batches.isEmpty()) {
                ready.addLast(queue);
            }
            queue.batches.addLast(new Waiting(packed, taking));
            queue.room += taking;
            queued += packed.size();
            queuedRoom += taking;
            borrowed += borrowing;
            offered.signal();
            return true;
        } finally {
            lock.unlock();
        }
    }

    // The next batch in round-robin order over the applications, waiting at most the given time for one. The calling thread unpacks it once it
    // released the lock.
    public Optional<Drained> drain(Duration wait) throws InterruptedException {
        Waiting waiting;
        Shedding shedding;
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
            waiting = queue.batches.pollFirst();
            borrowed -= borrowedBy(queue.room) - borrowedBy(queue.room - waiting.room());
            queue.room -= waiting.room();
            queued -= waiting.batch().size();
            queuedRoom -= waiting.room();
            drained += waiting.batch().size();
            shedding = shedding(queue);
            if (!queue.batches.isEmpty()) {
                ready.addLast(queue);
            }
        } finally {
            lock.unlock();
        }
        return Optional.of(new Drained(waiting.batch().batch(), shedding));
    }

    // Forgets the queue of an application admission retired, returning its share to the others, unless it was admitted again and holds batches.
    public void retire(ApplicationName application) {
        lock.lock();
        try {
            var queue = queues.computeIfPresent(application, (ignored, kept) -> kept.batches.isEmpty() ? null : kept);
            if (queue == null) {
                full.remove(application);
                share();
            }
        } finally {
            lock.unlock();
        }
    }

    public QueueHealth health() {
        lock.lock();
        try {
            return new QueueHealth(queued, queuedRoom, drained, Map.copyOf(full));
        } finally {
            lock.unlock();
        }
    }

    // Splits the capacity again over the applications, which passes over every queue only when one is added or forgotten.
    private void share() {
        reserved = queues.isEmpty() ? 0 : capacity / (2L * queues.size());
        pool = capacity - reserved * queues.size();
        borrowed = queues.values().stream().mapToLong(queue -> borrowedBy(queue.room)).sum();
    }

    private long borrowedBy(long room) {
        return Math.max(0, room - reserved);
    }

    private long halfPool() {
        return Math.max(1, pool / 2);
    }

    private Shedding shedding(Queue queue) {
        var borrowing = borrowedBy(queue.room);
        if (borrowing == 0) {
            return Shedding.NONE;
        }
        var fill = Math.max((double) borrowed / pool, (double) borrowing / halfPool());
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

    private record Waiting(QueuedBatch batch, long room) {
    }

    private static final class Queue {

        private final ArrayDeque<Waiting> batches = new ArrayDeque<>();
        private long room;
    }
}
