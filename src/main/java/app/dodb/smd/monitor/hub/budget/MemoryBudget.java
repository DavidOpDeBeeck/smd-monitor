package app.dodb.smd.monitor.hub.budget;

import static app.dodb.smd.monitor.hub.budget.HeapCheck.Exceeds;
import static app.dodb.smd.monitor.hub.budget.HeapCheck.Fits;
import static app.dodb.smd.monitor.hub.budget.HeapCheck.Warns;

// The one budget that bounds what the hub keeps (TR7), split by fixed ceilings (M1): the intake queues take up to 10%, counting up to 50%, and
// the join state up to 25%; the specimens take the rest of what those leave. Each product is accounted by the constants of Footprint.
public final class MemoryBudget {

    private static final double MAX_HEAP_SHARE = 0.75;
    private static final double LARGE_HEAP_SHARE = 0.25;

    private final long total;
    private final long queues;
    private final long counting;
    private final long join;
    private final long specimens;

    MemoryBudget(long total, long queues, long counting, long join, long specimens) {
        if (total < 1 || queues < 1 || counting < 1 || join < 1 || specimens < 1) {
            throw new IllegalArgumentException("MemoryBudget requires positive bytes for the hub and each product; got " + total + " for the hub, " + queues
                + " for the queues, " + counting + " for counting, " + join + " for the join, and " + specimens + " for the specimens");
        }
        this.total = total;
        this.queues = queues;
        this.counting = counting;
        this.join = join;
        this.specimens = specimens;
    }

    public static MemoryBudget of(long bytes) {
        return new MemoryBudget(bytes, bytes / 10, bytes / 2, bytes / 4, bytes);
    }

    // Checks the budget against the JVM's maximum heap at startup: the hub fails to start when the budget takes more than 75% of it, and warns
    // above 25%. A JVM without a maximum heap reports Long.MAX_VALUE, which every budget fits.
    public static HeapCheck checkHeap(long budget, long maxHeap) {
        if (budget > maxHeap * MAX_HEAP_SHARE) {
            return new Exceeds("smd.monitor.hub.memory of " + budget + " bytes exceeds 75% of the maximum heap of " + maxHeap
                + " bytes; lower it or raise -Xmx, so the application keeps room for its own objects");
        }
        if (budget > maxHeap * LARGE_HEAP_SHARE) {
            return new Warns("smd.monitor.hub.memory of " + budget + " bytes takes more than 25% of the maximum heap of " + maxHeap
                + " bytes; the hub may leave the application too little");
        }
        return new Fits();
    }

    public long total() {
        return total;
    }

    // The ceiling of the intake queues, which the hub sets aside apart from the other products (P1).
    public long queues() {
        return queues;
    }

    public long counting() {
        return counting;
    }

    public long join() {
        return join;
    }

    // What the specimens may take while counting and the join take the given bytes.
    public long specimens(long counted, long joined) {
        return Math.max(0, Math.min(specimens, total - queues - counted - joined));
    }

    // The spans the intake queues hold in all.
    public int queuedSpans() {
        return (int) Math.clamp(queues / Footprint.QUEUED_SPAN, 1, Integer.MAX_VALUE);
    }
}
