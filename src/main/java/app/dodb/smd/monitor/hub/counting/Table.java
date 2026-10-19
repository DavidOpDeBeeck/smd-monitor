package app.dodb.smd.monitor.hub.counting;

// What a table of counts is kept for, each keyed by the catalog's IDs of its kind. A timed table keeps a cell with a latency histogram per key;
// an occurrence table only counts.
public enum Table {
    // Each dispatch and handling: started, entered, failed, and latency.
    STAGE(true),
    // What each instance dispatched and handled.
    INSTANCE_DISPATCH(true),
    INSTANCE_HANDLING(true),
    // How long after its publication returned each processing group's handling started.
    DELAY(true),
    LINK(false),
    CROSSING(false),
    GROUP_LINK(false),
    EVENT_ROUTE(false),
    SINK_ROUTE(false),
    // The failed invocations of each stage and exception type.
    FAILURE(false);

    private final boolean timed;

    Table(boolean timed) {
        this.timed = timed;
    }

    boolean timed() {
        return timed;
    }
}
