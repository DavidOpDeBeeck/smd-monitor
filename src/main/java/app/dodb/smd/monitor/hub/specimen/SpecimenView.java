package app.dodb.smd.monitor.hub.specimen;

import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static java.util.Objects.requireNonNull;

// The specimens and traces of one epoch, which never change: the chunks the writer shared with it, so building it copied only the chunks that
// changed since the epoch before (J7). Queries read it from any thread.
public final class SpecimenView {

    private final SpecimenChunk[] chunks;
    private final TraceChunk[] traces;
    private final int specimens;

    SpecimenView(SpecimenChunk[] chunks, TraceChunk[] traces, int specimens) {
        this.chunks = requireNonNull(chunks);
        this.traces = requireNonNull(traces);
        this.specimens = specimens;
    }

    public int size() {
        return specimens;
    }

    public Optional<Specimen> specimen(long id) {
        var chunk = chunkOf(id);
        if (chunk == null) {
            return Optional.empty();
        }
        var offset = chunk.offsetOf(id);
        return offset < 0 ? Optional.empty() : Optional.ofNullable(chunk.specimens[offset]);
    }

    public Optional<TraceSnapshot> traceOf(long id) {
        var chunk = chunkOf(id);
        if (chunk == null) {
            return Optional.empty();
        }
        var offset = chunk.offsetOf(id);
        if (offset < 0 || chunk.specimens[offset] == null) {
            return Optional.empty();
        }
        var slot = chunk.traces[offset];
        return Optional.ofNullable(traces[slot / TraceChunk.SIZE].snapshots[slot % TraceChunk.SIZE]);
    }

    // Oldest first.
    public void forEach(Consumer<Specimen> action) {
        for (var chunk : chunks) {
            for (var offset = 0; offset < chunk.size; offset++) {
                var specimen = chunk.specimens[offset];
                if (specimen != null) {
                    action.accept(specimen);
                }
            }
        }
    }

    // Newest first, until the visitor answers false.
    public void forEachNewest(Predicate<Specimen> visitor) {
        for (var index = chunks.length - 1; index >= 0; index--) {
            var chunk = chunks[index];
            for (var offset = chunk.size - 1; offset >= 0; offset--) {
                var specimen = chunk.specimens[offset];
                if (specimen != null && !visitor.test(specimen)) {
                    return;
                }
            }
        }
    }

    // Newest first, over the chunks that hold a specimen completed at or after the time; the visitor still sees older ones of those chunks.
    public void forEachNewestCompletedFrom(long fromNanos, Predicate<Specimen> visitor) {
        for (var index = chunks.length - 1; index >= 0; index--) {
            var chunk = chunks[index];
            if (chunk.latestCompletedNanos < fromNanos) {
                continue;
            }
            for (var offset = chunk.size - 1; offset >= 0; offset--) {
                var specimen = chunk.specimens[offset];
                if (specimen != null && !visitor.test(specimen)) {
                    return;
                }
            }
        }
    }

    public void forEachTrace(Consumer<TraceSnapshot> action) {
        for (var chunk : traces) {
            for (var snapshot : chunk.snapshots) {
                if (snapshot != null) {
                    action.accept(snapshot);
                }
            }
        }
    }

    // The chunks shared with the writer, which later epochs share too until the writer changes them.
    SpecimenChunk[] chunks() {
        return chunks;
    }

    private SpecimenChunk chunkOf(long id) {
        var low = 0;
        var high = chunks.length - 1;
        while (low <= high) {
            var middle = (low + high) >>> 1;
            var chunk = chunks[middle];
            if (id < chunk.firstId()) {
                high = middle - 1;
            } else if (id > chunk.lastId()) {
                low = middle + 1;
            } else {
                return chunk;
            }
        }
        return null;
    }
}
