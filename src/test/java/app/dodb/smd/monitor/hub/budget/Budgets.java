package app.dodb.smd.monitor.hub.budget;

// Memory budgets for tests: generous for every product but the one a test squeezes, so it alone gives way.
public final class Budgets {

    private static final long PLENTY = 1L << 40;
    private static final int QUEUED_SPANS = 65_536;

    private Budgets() {
    }

    // Nothing gives way; the queues hold 65,536 packed spans in all.
    public static MemoryBudget generous() {
        return queuing(QUEUED_SPANS * Footprint.QUEUED_SPAN);
    }

    // The queues hold the given bytes of packed batches; nothing else gives way.
    public static MemoryBudget queuing(long bytes) {
        return new MemoryBudget(PLENTY, bytes, PLENTY, PLENTY, PLENTY);
    }

    // The specimens fit about the given number of invocations, each in a trace of its own with its parent found; nothing else gives way.
    public static MemoryBudget retaining(int invocations) {
        return specimens(invocations * (Footprint.SPECIMEN + Footprint.TRACE + Footprint.PARENT));
    }

    public static MemoryBudget specimens(long bytes) {
        return new MemoryBudget(PLENTY, QUEUED_SPANS * Footprint.QUEUED_SPAN, PLENTY, PLENTY, bytes);
    }

    public static MemoryBudget join(long bytes) {
        return new MemoryBudget(PLENTY, QUEUED_SPANS * Footprint.QUEUED_SPAN, PLENTY, bytes, PLENTY);
    }

    // Only the whole gives way, the given bytes beyond the queues: the specimens first, then the join, then counting (TR10).
    public static MemoryBudget total(long bytes) {
        var queues = QUEUED_SPANS * Footprint.QUEUED_SPAN;
        return new MemoryBudget(queues + bytes, queues, PLENTY, PLENTY, PLENTY);
    }

    public static MemoryBudget counting(long bytes) {
        return new MemoryBudget(PLENTY, QUEUED_SPANS * Footprint.QUEUED_SPAN, bytes, PLENTY, PLENTY);
    }
}
