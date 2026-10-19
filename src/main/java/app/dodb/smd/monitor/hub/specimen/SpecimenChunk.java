package app.dodb.smd.monitor.hub.specimen;

import java.util.Arrays;

// Up to 4,096 specimens in ascending sequence (J7), with the trace slot of each; an evicted specimen leaves a hole that keeps its ID, so the IDs
// stay sorted for lookups. The latest completion of any specimen it took bounds those it holds, so a query for a recent period passes over older
// chunks. Once an epoch shares the chunk, the writer changes a copy of it instead.
final class SpecimenChunk {

    static final int SIZE = 4_096;

    final long[] ids;
    final Specimen[] specimens;
    final int[] traces;
    int size;
    int live;
    long latestCompletedNanos = Long.MIN_VALUE;
    boolean shared;

    SpecimenChunk() {
        this(new long[SIZE], new Specimen[SIZE], new int[SIZE], 0, 0, Long.MIN_VALUE);
    }

    private SpecimenChunk(long[] ids, Specimen[] specimens, int[] traces, int size, int live, long latestCompletedNanos) {
        this.ids = ids;
        this.specimens = specimens;
        this.traces = traces;
        this.size = size;
        this.live = live;
        this.latestCompletedNanos = latestCompletedNanos;
    }

    SpecimenChunk copy() {
        return new SpecimenChunk(Arrays.copyOf(ids, SIZE), Arrays.copyOf(specimens, SIZE), Arrays.copyOf(traces, SIZE), size, live, latestCompletedNanos);
    }

    void add(Specimen specimen, int traceSlot) {
        ids[size] = specimen.id();
        specimens[size] = specimen;
        traces[size] = traceSlot;
        size++;
        live++;
        latestCompletedNanos = Math.max(latestCompletedNanos, specimen.completedAtNanos());
    }

    boolean full() {
        return size == SIZE;
    }

    long firstId() {
        return ids[0];
    }

    long lastId() {
        return ids[size - 1];
    }

    // The offset of the ID, or a negative number when the chunk never held it.
    int offsetOf(long id) {
        return Arrays.binarySearch(ids, 0, size, id);
    }
}
