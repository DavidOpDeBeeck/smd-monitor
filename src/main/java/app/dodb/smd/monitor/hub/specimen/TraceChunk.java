package app.dodb.smd.monitor.hub.specimen;

import java.util.Arrays;

// The snapshots of up to 4,096 trace slots. Once an epoch shares the chunk, the writer changes a copy of it instead.
final class TraceChunk {

    static final int SIZE = 4_096;

    final TraceSnapshot[] snapshots;
    boolean shared;

    TraceChunk() {
        this(new TraceSnapshot[SIZE]);
    }

    private TraceChunk(TraceSnapshot[] snapshots) {
        this.snapshots = snapshots;
    }

    TraceChunk copy() {
        return new TraceChunk(Arrays.copyOf(snapshots, SIZE));
    }
}
