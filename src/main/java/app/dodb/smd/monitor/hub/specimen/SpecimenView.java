package app.dodb.smd.monitor.hub.specimen;

import app.dodb.smd.monitor.hub.span.ApplicationName;

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
        forEach(Optional.empty(), action);
    }

    // Oldest first, of the application when one is given.
    public void forEach(Optional<ApplicationName> application, Consumer<Specimen> action) {
        var only = application.orElse(null);
        for (var chunk : chunks) {
            for (var offset = 0; offset < chunk.size; offset++) {
                var specimen = in(chunk, offset, only);
                if (specimen != null) {
                    action.accept(specimen);
                }
            }
        }
    }

    // Oldest first, the failed only.
    public void forEachFailed(Consumer<Specimen> action) {
        for (var chunk : chunks) {
            for (var word = 0; word < chunk.failed.length; word++) {
                for (var bits = chunk.failed[word]; bits != 0; bits &= bits - 1) {
                    var specimen = chunk.specimens[word * Long.SIZE + Long.numberOfTrailingZeros(bits)];
                    if (specimen != null) {
                        action.accept(specimen);
                    }
                }
            }
        }
    }

    // Newest first, of the application when one is given, until the visitor answers false.
    public void forEachNewest(Optional<ApplicationName> application, Predicate<Specimen> visitor) {
        forEachNewestCompletedFrom(Long.MIN_VALUE, application, visitor);
    }

    // Newest first, of the application when one is given, over the chunks that hold a specimen completed at or after the time; the visitor still sees
    // older ones of those chunks.
    public void forEachNewestCompletedFrom(long fromNanos, Optional<ApplicationName> application, Predicate<Specimen> visitor) {
        var only = application.orElse(null);
        for (var index = chunks.length - 1; index >= 0; index--) {
            var chunk = chunks[index];
            if (chunk.latestCompletedNanos < fromNanos) {
                continue;
            }
            for (var offset = chunk.size - 1; offset >= 0; offset--) {
                var specimen = in(chunk, offset, only);
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

    // The slot's specimen when it holds one of the application, or of any when none is given; the others are passed over without reading them.
    private static Specimen in(SpecimenChunk chunk, int offset, ApplicationName only) {
        return only == null || only.equals(chunk.applications[offset]) ? chunk.specimens[offset] : null;
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
