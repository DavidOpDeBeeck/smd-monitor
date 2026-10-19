package app.dodb.smd.monitor.hub.health;

// Why the hub refused, dropped, or kept apart a span, send, or invocation, counted per application since it appeared (FR7).
public enum HealthReason {
    // Admission refused the spans of an instance beyond the application's limit, or that started too far ahead of the hub's clock (T6).
    REFUSED_BY_INSTANCE_LIMIT,
    REFUSED_AHEAD_OF_CLOCK,
    // The application's intake queue was full; the exporter was told to send again later.
    QUEUE_FULL,
    // Shedding counted the spans but did not retain them, or did not index and link them either (C5).
    SHED_UNRETAINED,
    SHED_UNJOINED,
    // A span or send that arrived again while the span index held it (J3).
    DUPLICATE,
    // A span counted in a second that had already settled (T3), or not counted as it started before the history (T5).
    REVISED,
    BEYOND_HISTORY,
    // A child that left the span index without its parent, by the reason (J5).
    PARENT_NEVER_RECEIVED,
    PARENT_AFTER_HORIZON,
    // A retained child whose parent the hub does not retain, or whose trace reached its limit (J6).
    FRAGMENT,
    // A span, link, send, or failure counted under an other key, as its kind reached its cap (A1).
    OVERFLOW,
    // A retained invocation the hub dropped with its trace, as no reservoir kept it.
    EVICTED,
    // The spans of a batch the writer dropped as it failed (C10).
    FAILED_BATCH
}
