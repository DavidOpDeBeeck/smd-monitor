package app.dodb.smd.monitor.hub.specimen;

import app.dodb.smd.monitor.hub.span.ApplicationName;

import java.util.Arrays;

// Up to 4,096 specimens in ascending sequence (J7), with the trace slot of each; an evicted specimen leaves a hole that keeps its ID, so the IDs
// stay sorted for lookups. The latest completion of any specimen it took bounds those it holds, so a query for a recent period passes over older
// chunks. Each slot keeps its specimen's application and whether it failed, which a specimen that replaces it shares, so a query of one
// application or of the failures passes over the other slots without reading their specimens. Once an epoch shares the chunk, the writer changes a
// copy of it instead.
final class SpecimenChunk {

    static final int SIZE = 4_096;

    final long[] ids;
    final Specimen[] specimens;
    final int[] traces;
    final ApplicationName[] applications;
    // A bit per slot, set when its specimen failed.
    final long[] failed;
    int size;
    int live;
    long latestCompletedNanos = Long.MIN_VALUE;
    boolean shared;

    SpecimenChunk() {
        this(new long[SIZE], new Specimen[SIZE], new int[SIZE], new ApplicationName[SIZE], new long[SIZE / Long.SIZE], 0, 0, Long.MIN_VALUE);
    }

    private SpecimenChunk(long[] ids, Specimen[] specimens, int[] traces, ApplicationName[] applications, long[] failed, int size, int live,
                          long latestCompletedNanos) {
        this.ids = ids;
        this.specimens = specimens;
        this.traces = traces;
        this.applications = applications;
        this.failed = failed;
        this.size = size;
        this.live = live;
        this.latestCompletedNanos = latestCompletedNanos;
    }

    SpecimenChunk copy() {
        return new SpecimenChunk(Arrays.copyOf(ids, SIZE), Arrays.copyOf(specimens, SIZE), Arrays.copyOf(traces, SIZE), Arrays.copyOf(applications, SIZE),
            failed.clone(), size, live, latestCompletedNanos);
    }

    void add(Specimen specimen, int traceSlot) {
        ids[size] = specimen.id();
        specimens[size] = specimen;
        traces[size] = traceSlot;
        applications[size] = specimen.application();
        if (specimen.failed()) {
            failed[size / Long.SIZE] |= 1L << size;
        }
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
