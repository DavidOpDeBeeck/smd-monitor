package app.dodb.smd.monitor.hub.health;

import static java.util.Objects.requireNonNull;

// What the hub takes of its memory budget, product by product (M1).
public record MemoryUse(long budget, ProductUse queues, ProductUse counting, ProductUse join, ProductUse specimens) {

    public MemoryUse {
        requireNonNull(queues);
        requireNonNull(counting);
        requireNonNull(join);
        requireNonNull(specimens);
    }

    public long bytes() {
        return queues.bytes() + counting.bytes() + join.bytes() + specimens.bytes();
    }
}
