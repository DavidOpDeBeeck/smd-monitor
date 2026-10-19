package app.dodb.smd.monitor.hub.intake;

// How much of a batch the hub processes, by how full its application's intake queue was when the writer took it. Counting is never shed.
public enum Shedding {
    NONE,
    // The queue was at least half full: only failed and slow spans become specimens.
    INTERESTING_ONLY,
    // The queue was at least 80% full: spans are deduplicated, but neither indexed nor joined.
    UNJOINED
}
