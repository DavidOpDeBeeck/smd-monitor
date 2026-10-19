package app.dodb.smd.monitor.hub.specimen;

import app.dodb.smd.monitor.hub.span.ApplicationName;
import app.dodb.smd.monitor.hub.span.MessageKind;

import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.LongFunction;

// A retained trace as the writer grows it (J6): the members linked into one component, merged by size as links arrive, with the figures its
// snapshot shows. A component merged into another points to it, so what referred to the smaller one finds the trace. It is held while Recent
// holds it or any reservoir entry refers to it. Most traces hold a single invocation, so it keeps only numbers; what needs the members, such as
// their applications, is read from them. Owned by the writer.
final class TraceComponent {

    final int slot;
    private TraceComponent absorbedInto;
    long[] members = new long[1];
    int size;
    long root;
    long rootStartNanos;
    MessageKind rootKind;
    boolean rootDispatch;
    int handlings;
    int failures;
    long endNanos;
    final int[] dispatches = new int[MessageKind.values().length];
    boolean inRecent = true;
    int reservoirEntries;
    boolean dirty = true;
    boolean dropped;
    private TraceSnapshot snapshot;

    TraceComponent(int slot) {
        this.slot = slot;
    }

    // The component this one was merged into, with the path compressed.
    TraceComponent find() {
        var current = this;
        while (current.absorbedInto != null) {
            current = current.absorbedInto;
        }
        var step = this;
        while (step.absorbedInto != null && step.absorbedInto != current) {
            var next = step.absorbedInto;
            step.absorbedInto = current;
            step = next;
        }
        return current;
    }

    void add(Specimen specimen) {
        if (size == members.length) {
            members = Arrays.copyOf(members, size * 2);
        }
        members[size++] = specimen.id();
        if (size == 1) {
            root = specimen.id();
            rootStartNanos = specimen.startedAtNanos();
            rootDispatch = !specimen.handling();
            rootKind = specimen.type().kind();
        }
        handlings += specimen.handling() ? 1 : 0;
        failures += specimen.failed() ? 1 : 0;
        if (!specimen.handling()) {
            dispatches[specimen.type().kind().ordinal()]++;
        }
        endNanos = Math.max(endNanos, specimen.completedAtNanos());
        dirty = true;
    }

    // Absorbs the other component, whose root now continues a member of this one; the caller names the root.
    void absorb(TraceComponent other) {
        if (size + other.size > members.length) {
            members = Arrays.copyOf(members, Math.max(size + other.size, members.length * 2));
        }
        System.arraycopy(other.members, 0, members, size, other.size);
        size += other.size;
        handlings += other.handlings;
        failures += other.failures;
        for (var kind = 0; kind < dispatches.length; kind++) {
            dispatches[kind] += other.dispatches[kind];
        }
        endNanos = Math.max(endNanos, other.endNanos);
        reservoirEntries += other.reservoirEntries;
        other.absorbedInto = this;
        dirty = true;
    }

    boolean held() {
        return inRecent || reservoirEntries > 0;
    }

    // The applications come in the order their members started, those that started at once in the order they arrived.
    TraceSnapshot snapshot(LongFunction<Specimen> specimens) {
        if (dirty || snapshot == null) {
            var sorted = Arrays.copyOf(members, size);
            Arrays.sort(sorted);
            var present = Arrays.stream(sorted).mapToObj(specimens).filter(Objects::nonNull).toList();
            var threads = new HashSet<String>();
            present.forEach(member -> threads.add(member.threadName()));
            var applications = new LinkedHashSet<ApplicationName>();
            present.stream().sorted(Comparator.comparingLong(Specimen::startedAtNanos)).forEach(member -> applications.add(member.application()));
            var composition = new EnumMap<MessageKind, Integer>(MessageKind.class);
            for (var kind : MessageKind.values()) {
                composition.put(kind, dispatches[kind.ordinal()] - (rootDispatch && rootKind == kind ? 1 : 0));
            }
            snapshot = new TraceSnapshot(root, rootStartNanos, sorted, handlings, failures, endNanos, threads.size(), List.copyOf(applications), composition);
            dirty = false;
        }
        return snapshot;
    }
}
