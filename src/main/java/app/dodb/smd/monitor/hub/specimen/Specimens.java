package app.dodb.smd.monitor.hub.specimen;

import app.dodb.smd.monitor.hub.FailureCause;
import app.dodb.smd.monitor.hub.InvocationId;
import app.dodb.smd.monitor.hub.ParentSummary;
import app.dodb.smd.monitor.hub.budget.FairShare;
import app.dodb.smd.monitor.hub.budget.Footprint;
import app.dodb.smd.monitor.hub.join.SpanKey;
import app.dodb.smd.monitor.hub.span.ApplicationName;
import app.dodb.smd.monitor.hub.span.InstanceName;
import app.dodb.smd.monitor.hub.span.MessageSpan;
import app.dodb.smd.monitor.hub.span.Stage;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.function.Consumer;

import static java.util.Objects.requireNonNull;

// What the hub retains of its spans, owned by its writer. Every specimen enters Recent in a trace of its own, and traces merge as links join them
// (J6). Recent keeps the traces of each application first in, first out, until the specimens no longer fit their allowance of the memory budget;
// a trace Recent lets go is judged by the reservoirs, and dropped whole when none keeps it (M4, M5). A trace that crosses applications counts
// against each by its own members, and its trace by the hub. Each tick publishes a view that copies only the chunks that changed.
public final class Specimens {

    // A trace grows to at most this many members; a link that would take it beyond leaves the child a fragment of its own (J6).
    static final int MAX_TRACE = 10_000;
    private static final double GROW_BELOW = 0.8;

    private final Consumer<SpanKey> released;
    private final ArrayList<SpecimenChunk> chunks = new ArrayList<>();
    private final ArrayList<TraceChunk> traceChunks = new ArrayList<>();
    private TraceComponent[] components = new TraceComponent[TraceChunk.SIZE];
    private final ArrayDeque<Integer> freeSlots = new ArrayDeque<>();
    private int nextSlot;
    private final Map<ApplicationName, Retaining> applications = new HashMap<>();
    private final Reservoirs reservoirs = new Reservoirs();
    private final LinkedHashSet<TraceComponent> changed = new LinkedHashSet<>();
    // The applications by their bytes, kept from one fit to the next, as a fit follows every batch and ordering every application each time
    // would cost the writer more the more applications report. Each application has at most one current entry, with the bytes it was queued
    // with; an entry no longer current is skipped when it comes up. An application that grew since is queued again before a fit.
    private final PriorityQueue<Using> furthest = new PriorityQueue<>(Comparator.comparingLong(Using::bytes).reversed());
    private final ArrayList<Retaining> grown = new ArrayList<>();
    private int live;
    private int traces;
    // The bytes of every application's specimens and parents, kept with theirs, so fitting does not add them up again for each trace it drops.
    private long retainedBytes;
    private long windowStartNanos = Long.MIN_VALUE;

    // The released consumer learns the span of every specimen the hub drops, so the span index lets it go.
    public Specimens(Consumer<SpanKey> released) {
        this.released = requireNonNull(released);
    }

    public void retain(InvocationId id, ApplicationName application, InstanceName instance, MessageSpan span) {
        var retaining = retaining(application);
        var specimen = Specimen.of(id, application, instance, span, retaining.names);
        var trace = newTrace();
        trace.add(specimen);
        append(specimen, trace.slot);
        live++;
        retaining.retained++;
        retainedBytes += Footprint.SPECIMEN;
        retaining.byStage.merge(specimen.stage(), 1, Integer::sum);
        retaining.recent.addLast(trace);
        mayGiveWay(retaining);
    }

    public boolean retains(InvocationId id) {
        return specimenAt(id.value()) != null;
    }

    // The child keeps a summary of its parent; when the hub retains the parent too, their traces merge, unless that would close a cycle or grow
    // the trace beyond its limit. A child whose parent the hub does not retain is a fragment: the parent was dropped, or never retained.
    public void parentFound(ParentSummary parent) {
        var childPosition = position(parent.child().value());
        if (childPosition == null || childPosition.specimen() == null) {
            return;
        }
        var child = childPosition.specimen().withParent(parent);
        if (childPosition.specimen().parent().isEmpty()) {
            var retaining = retaining(child.application());
            retaining.parents++;
            retainedBytes += Footprint.PARENT;
            grew(retaining);
        }
        writable(childPosition.chunk()).specimens[childPosition.offset()] = child;
        var childTrace = components[childPosition.traceSlot()].find();
        var parentPosition = position(parent.parent().value());
        if (parentPosition == null || parentPosition.specimen() == null) {
            retaining(child.application()).fragments++;
            return;
        }
        var parentTrace = components[parentPosition.traceSlot()].find();
        if (parentTrace == childTrace) {
            return;
        }
        if (parentTrace.size + childTrace.size > MAX_TRACE) {
            retaining(child.application()).fragments++;
            return;
        }
        merge(parentTrace, childTrace);
    }

    // Drops every trace with a member of the application, then the application.
    public void retire(ApplicationName application) {
        var traces = new LinkedHashSet<TraceComponent>();
        for (var chunk : chunks) {
            for (var offset = 0; offset < chunk.size; offset++) {
                var specimen = chunk.specimens[offset];
                if (specimen != null && specimen.application().equals(application)) {
                    traces.add(components[chunk.traces[offset]].find());
                }
            }
        }
        for (var trace : traces) {
            reservoirs.forget(trace);
            drop(trace);
        }
        reservoirs.retire(application);
        var retired = applications.remove(application);
        if (retired != null) {
            retainedBytes -= retired.bytes();
        }
    }

    // Lets go of whole traces until the specimens fit the allowance (TR10): of the application furthest above its fair share, its oldest trace
    // in Recent, which the reservoirs judge; once its Recent is empty, its Slowest K and then its Failures K. An application within its share
    // keeps what it retains (TR9). False when every application above its share reached the floor of one trace per failure.
    public boolean fit(long allowance) {
        if (bytes() <= allowance) {
            return true;
        }
        if (furthest.size() > 2 * applications.size() + 64) {
            furthest.clear();
            applications.values().forEach(application -> application.queuedBytes = -1);
            applications.values().forEach(this::queue);
        } else {
            grown.forEach(this::queue);
        }
        grown.clear();
        // While fitting, an application's bytes only fall and the share only grows, as traces leave: one within its share stays within it. An
        // application keeps giving way while it is still the furthest, as an entry's bytes are at least its application's.
        while (bytes() > allowance) {
            var using = furthest.poll();
            if (using == null) {
                return false;
            }
            var retaining = using.retaining;
            if (retaining.queuedBytes != using.bytes || applications.get(retaining.name) != retaining) {
                continue;
            }
            retaining.queuedBytes = -1;
            if (retaining.bytes() != using.bytes) {
                queue(retaining);
                continue;
            }
            if (using.bytes <= share(allowance)) {
                queue(retaining);
                return false;
            }
            if (giveWay(retaining, allowance)) {
                queue(retaining);
            } else {
                retaining.floored = true;
            }
        }
        return true;
    }

    // Lets go of the application's traces while the specimens do not fit and it stays the furthest above its share; false once it reached
    // its floor.
    private boolean giveWay(Retaining retaining, long allowance) {
        var next = furthest.peek();
        do {
            if (!leaveOldestRecent(retaining)) {
                var displaced = reservoirs.shrink(retaining.name);
                if (displaced.isEmpty()) {
                    return false;
                }
                displaced.get().forEach(this::releaseEntry);
            }
        } while (bytes() > allowance && retaining.bytes() > share(allowance) && (next == null || retaining.bytes() >= next.bytes));
        return true;
    }

    // An application at its floor may give way again once Recent holds a trace of it, or its reservoirs grew.
    private void mayGiveWay(Retaining retaining) {
        retaining.floored = false;
        grew(retaining);
    }

    private void grew(Retaining retaining) {
        if (!retaining.grown) {
            retaining.grown = true;
            grown.add(retaining);
        }
    }

    // Queues the application with its bytes now, unless its current entry has them or it reached its floor. A fit lets go of the entries no
    // longer current once they outnumber the applications.
    private void queue(Retaining retaining) {
        retaining.grown = false;
        if (retaining.floored || retaining.queuedBytes == retaining.bytes() || applications.get(retaining.name) != retaining) {
            return;
        }
        retaining.queuedBytes = retaining.bytes();
        furthest.add(new Using(retaining, retaining.queuedBytes));
    }

    private long share(long allowance) {
        return FairShare.of(allowance - (long) traces * Footprint.TRACE, applications.size());
    }

    // Raises the K of every application one step while the specimens take less than 80% of the allowance.
    public void relax(long allowance) {
        if (bytes() < allowance * GROW_BELOW) {
            applications.values().forEach(retaining -> {
                if (reservoirs.grow(retaining.name)) {
                    mayGiveWay(retaining);
                }
            });
        }
    }

    public long namesBytes(ApplicationName application) {
        var retaining = applications.get(application);
        return retaining == null ? 0 : retaining.names.bytes();
    }

    // The specimens of every application, their parents, and their traces. Their names are accounted with counting, as the catalog's are.
    public long bytes() {
        return retainedBytes + (long) traces * Footprint.TRACE;
    }

    // Lets the dispatches that started before the window leave Slowest.
    public void expire(long windowStartNanos) {
        this.windowStartNanos = windowStartNanos;
        reservoirs.expire(windowStartNanos).forEach(this::releaseEntry);
    }

    // Compacts sparse chunks, and publishes the specimens and the traces that changed.
    public SpecimenView tick() {
        compact();
        for (var trace : changed) {
            if (!trace.dropped) {
                writableTraces(trace.slot / TraceChunk.SIZE).snapshots[trace.slot % TraceChunk.SIZE] = trace.snapshot(this::specimenAt);
            }
        }
        changed.clear();
        var shared = chunks.stream().filter(chunk -> chunk.size > 0).toArray(SpecimenChunk[]::new);
        chunks.forEach(chunk -> chunk.shared = true);
        traceChunks.forEach(chunk -> chunk.shared = true);
        return new SpecimenView(shared, traceChunks.toArray(TraceChunk[]::new), live);
    }

    public Retention retention(ApplicationName application) {
        var retaining = applications.get(application);
        if (retaining == null) {
            return Retention.NONE;
        }
        return new Retention(retaining.retained, retaining.evicted, retaining.fragments, retaining.bytes(), retaining.names.bytes(), retaining.droppedThroughNanos,
            reservoirs.slowestK(application), reservoirs.failuresK(application));
    }

    // The specimens the application retains of the stage.
    public int retained(ApplicationName application, Stage stage) {
        var retaining = applications.get(application);
        return retaining == null ? 0 : retaining.byStage.getOrDefault(stage, 0);
    }

    // The oldest specimen each application retains, found in one pass from the oldest chunk.
    public Map<ApplicationName, Specimen> oldest() {
        var oldest = new HashMap<ApplicationName, Specimen>();
        var wanted = applications.values().stream().filter(retaining -> retaining.retained > 0).count();
        for (var chunk : chunks) {
            for (var offset = 0; offset < chunk.size && oldest.size() < wanted; offset++) {
                var specimen = chunk.specimens[offset];
                if (specimen != null) {
                    oldest.putIfAbsent(specimen.application(), specimen);
                }
            }
            if (oldest.size() >= wanted) {
                break;
            }
        }
        return oldest;
    }

    private boolean leaveOldestRecent(Retaining retaining) {
        while (!retaining.recent.isEmpty()) {
            var trace = retaining.recent.pollFirst().find();
            if (trace.inRecent && !trace.dropped) {
                leaveRecent(trace);
                return true;
            }
        }
        return false;
    }

    private void leaveRecent(TraceComponent trace) {
        trace.inRecent = false;
        var members = members(trace);
        reservoirs.judge(trace, members, deepestFailure(trace, members), windowStartNanos).forEach(this::releaseEntry);
        if (!trace.held()) {
            drop(trace);
        }
    }

    private void releaseEntry(TraceComponent trace) {
        var current = trace.find();
        if (!current.held() && !current.dropped) {
            drop(current);
        }
    }

    private void drop(TraceComponent trace) {
        if (trace.dropped) {
            return;
        }
        trace.dropped = true;
        for (var index = 0; index < trace.size; index++) {
            var position = position(trace.members[index]);
            if (position == null || position.specimen() == null) {
                continue;
            }
            var chunk = writable(position.chunk());
            chunk.specimens[position.offset()] = null;
            chunk.live--;
            live--;
            var specimen = position.specimen();
            released.accept(specimen.key());
            var retaining = applications.get(specimen.application());
            if (retaining != null) {
                retaining.retained--;
                retaining.parents -= specimen.parent().isPresent() ? 1 : 0;
                retainedBytes -= Footprint.SPECIMEN + (specimen.parent().isPresent() ? Footprint.PARENT : 0);
                retaining.evicted++;
                retaining.droppedThroughNanos = Math.max(retaining.droppedThroughNanos, specimen.startedAtNanos());
                retaining.byStage.computeIfPresent(specimen.stage(), (stage, count) -> count == 1 ? null : count - 1);
            }
        }
        writableTraces(trace.slot / TraceChunk.SIZE).snapshots[trace.slot % TraceChunk.SIZE] = null;
        components[trace.slot] = null;
        freeSlots.push(trace.slot);
        traces--;
        changed.remove(trace);
    }

    // The smaller trace joins the larger; the root stays the parent trace's.
    private void merge(TraceComponent parentTrace, TraceComponent childTrace) {
        var larger = parentTrace.size >= childTrace.size ? parentTrace : childTrace;
        var smaller = larger == parentTrace ? childTrace : parentTrace;
        var root = parentTrace.root;
        var rootStartNanos = parentTrace.rootStartNanos;
        var rootKind = parentTrace.rootKind;
        var rootDispatch = parentTrace.rootDispatch;
        var rejoining = larger.inRecent ? (smaller.inRecent ? null : smaller) : (smaller.inRecent ? larger : null);
        var rejoiningApplications = rejoining == null ? List.<ApplicationName>of() : applicationsOf(rejoining);
        var inRecent = larger.inRecent || smaller.inRecent;
        for (var index = 0; index < smaller.size; index++) {
            var position = position(smaller.members[index]);
            if (position != null) {
                writable(position.chunk()).traces[position.offset()] = larger.slot;
            }
        }
        larger.absorb(smaller);
        larger.root = root;
        larger.rootStartNanos = rootStartNanos;
        larger.rootKind = rootKind;
        larger.rootDispatch = rootDispatch;
        larger.inRecent = inRecent;
        writableTraces(smaller.slot / TraceChunk.SIZE).snapshots[smaller.slot % TraceChunk.SIZE] = null;
        components[smaller.slot] = null;
        freeSlots.push(smaller.slot);
        traces--;
        changed.remove(smaller);
        changed.add(larger);
        // A trace that rejoins Recent is let go with the other traces of its applications.
        rejoiningApplications.forEach(application -> {
            var retaining = retaining(application);
            retaining.recent.addLast(larger);
            mayGiveWay(retaining);
        });
    }

    // The failure of the member deepest in the trace, which the Failures reservoir keys the trace by (F1).
    private Optional<FailureCause.Step> deepestFailure(TraceComponent trace, List<Specimen> members) {
        if (trace.failures == 0) {
            return Optional.empty();
        }
        var byId = new HashMap<Long, Specimen>();
        members.forEach(member -> byId.put(member.id(), member));
        Specimen deepest = null;
        var deepestDepth = -1;
        for (var member : members) {
            if (!member.failed()) {
                continue;
            }
            var depth = 0;
            var current = member;
            while (current.parent().isPresent() && depth < members.size()) {
                var parent = byId.get(current.parent().get().parent().value());
                if (parent == null) {
                    break;
                }
                depth++;
                current = parent;
            }
            if (depth > deepestDepth) {
                deepest = member;
                deepestDepth = depth;
            }
        }
        return Optional.ofNullable(deepest).map(failed -> new FailureCause.Step(failed.application(), failed.stage(), failed.failureType().orElseThrow()));
    }

    private List<ApplicationName> applicationsOf(TraceComponent trace) {
        return members(trace).stream().map(Specimen::application).distinct().toList();
    }

    private List<Specimen> members(TraceComponent trace) {
        var members = new ArrayList<Specimen>(trace.size);
        for (var index = 0; index < trace.size; index++) {
            var position = position(trace.members[index]);
            if (position != null && position.specimen() != null) {
                members.add(position.specimen());
            }
        }
        return members;
    }

    private TraceComponent newTrace() {
        var slot = freeSlots.isEmpty() ? nextSlot++ : freeSlots.pop();
        if (slot >= components.length) {
            components = Arrays.copyOf(components, components.length * 2);
        }
        while (slot / TraceChunk.SIZE >= traceChunks.size()) {
            traceChunks.add(new TraceChunk());
        }
        var trace = new TraceComponent(slot);
        components[slot] = trace;
        traces++;
        changed.add(trace);
        return trace;
    }

    private void append(Specimen specimen, int traceSlot) {
        if (chunks.isEmpty() || chunks.getLast().full()) {
            chunks.add(new SpecimenChunk());
        }
        writable(chunks.size() - 1).add(specimen, traceSlot);
    }

    // Merges neighbouring chunks that are each less than a quarter full, so traces kept long do not keep chunks of holes; not the newest chunk,
    // which still fills.
    private void compact() {
        for (var index = 0; index < chunks.size() - 1; index++) {
            var chunk = chunks.get(index);
            if (chunk.live == 0) {
                chunks.remove(index--);
                continue;
            }
            if (index + 1 >= chunks.size() - 1) {
                return;
            }
            var next = chunks.get(index + 1);
            if (chunk.live < SpecimenChunk.SIZE / 4 && next.live < SpecimenChunk.SIZE / 4) {
                var merged = new SpecimenChunk();
                for (var source : List.of(chunk, next)) {
                    for (var offset = 0; offset < source.size; offset++) {
                        if (source.specimens[offset] != null) {
                            merged.add(source.specimens[offset], source.traces[offset]);
                        }
                    }
                }
                chunks.set(index, merged);
                chunks.remove(index + 1);
                index--;
            }
        }
    }

    private SpecimenChunk writable(int index) {
        var chunk = chunks.get(index);
        if (!chunk.shared) {
            return chunk;
        }
        var copy = chunk.copy();
        chunks.set(index, copy);
        return copy;
    }

    private TraceChunk writableTraces(int index) {
        var chunk = traceChunks.get(index);
        if (!chunk.shared) {
            return chunk;
        }
        var copy = chunk.copy();
        traceChunks.set(index, copy);
        return copy;
    }

    private Specimen specimenAt(long id) {
        var position = position(id);
        return position == null ? null : position.specimen();
    }

    private Position position(long id) {
        var low = 0;
        var high = chunks.size() - 1;
        while (low <= high) {
            var middle = (low + high) >>> 1;
            var chunk = chunks.get(middle);
            if (chunk.size == 0 || id < chunk.firstId()) {
                high = middle - 1;
            } else if (id > chunk.lastId()) {
                low = middle + 1;
            } else {
                var offset = chunk.offsetOf(id);
                return offset < 0 ? null : new Position(middle, offset, chunk.specimens[offset], chunk.traces[offset]);
            }
        }
        return null;
    }

    private Retaining retaining(ApplicationName application) {
        return applications.computeIfAbsent(application, Retaining::new);
    }

    // What an application retains: its specimens and the parents found of them, its traces in the order Recent took them, and its names; and
    // the start of the latest specimen the hub dropped.
    private static final class Retaining {

        private final ApplicationName name;
        private final Names names = new Names();
        private final ArrayDeque<TraceComponent> recent = new ArrayDeque<>();
        private final Map<Stage, Integer> byStage = new HashMap<>();
        private int retained;
        private int parents;
        private long evicted;
        private long fragments;
        private long droppedThroughNanos = Long.MIN_VALUE;
        // The bytes of its current entry among the furthest, -1 while it has none; whether it grew since it was last queued; and whether it
        // reached its floor, with nothing in Recent and its reservoirs at their lowest K.
        private long queuedBytes = -1;
        private boolean grown;
        private boolean floored;

        private Retaining(ApplicationName name) {
            this.name = name;
        }

        private long bytes() {
            return retained * Footprint.SPECIMEN + parents * Footprint.PARENT;
        }
    }

    private record Position(int chunk, int offset, Specimen specimen, int traceSlot) {
    }

    // An application fitting may take from, with its bytes when it was queued.
    private record Using(Retaining retaining, long bytes) {
    }

    // The specimens an application retains, how many the hub dropped, and the children that began fragments; the heap its specimens and their
    // parents take and that of its names; the start of the latest specimen dropped, Long.MIN_VALUE while none was; and the K of its reservoirs.
    public record Retention(int retained, long evicted, long fragments, long bytes, long namesBytes, long droppedThroughNanos, int slowestK, int failuresK) {

        public static final Retention NONE = new Retention(0, 0, 0, 0, 0, Long.MIN_VALUE, Reservoirs.SLOWEST, Reservoirs.FAILURES);
    }
}
