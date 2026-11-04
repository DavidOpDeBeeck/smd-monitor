package app.dodb.smd.monitor.hub;

import app.dodb.smd.monitor.hub.span.ApplicationName;
import app.dodb.smd.monitor.hub.specimen.Specimen;
import app.dodb.smd.monitor.hub.specimen.SpecimenView;
import app.dodb.smd.monitor.hub.specimen.TraceSnapshot;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;

import static java.util.Map.Entry.comparingByValue;
import static java.util.Objects.requireNonNull;

// The answers to the page's queries over the specimens and traces of one epoch, which never change, so any thread asks without holding the hub.
// A query walks the specimens or the traces once; it builds an Invocation only for what it answers.
final class Queries {

    private final long sequence;
    private final SpecimenView view;
    private volatile FailureChains failureChains;

    Queries(long sequence, SpecimenView view) {
        this.sequence = sequence;
        this.view = requireNonNull(view);
    }

    TapePage tape(TapeQuery query) {
        var filter = query.filter();
        var minNanos = query.minDuration().toNanos();
        var olderThan = query.olderThan().map(InvocationId::value).orElse(Long.MAX_VALUE);
        var page = new ArrayList<RetainedInvocation>();
        var counts = new long[]{0, 0, -1};
        var more = new boolean[1];
        view.forEachNewest(filter.scope(), specimen -> {
            counts[0]++;
            if (!filter.matches(specimen) || specimen.durationNanos() < minNanos) {
                return true;
            }
            counts[1]++;
            counts[2] = Math.max(counts[2], specimen.durationNanos());
            if (specimen.id() >= olderThan) {
                return true;
            }
            if (page.size() < query.limit()) {
                page.add(retained(specimen));
            } else {
                more[0] = true;
            }
            return true;
        });
        var next = more[0] ? Optional.of(page.getLast().invocation().id()) : Optional.<InvocationId>empty();
        var slowest = counts[2] < 0 ? Optional.<Duration>empty() : Optional.of(Duration.ofNanos(counts[2]));
        return new TapePage(sequence, page, (int) counts[0], (int) counts[1], slowest, next);
    }

    DurationHistogram durations(InvocationFilter filter, Duration threshold, int buckets) {
        if (buckets < 1 || buckets > DurationHistogram.MAX_BUCKETS) {
            throw new IllegalArgumentException("A duration histogram requires between 1 and " + DurationHistogram.MAX_BUCKETS + " buckets; got " + buckets);
        }
        var counts = new int[buckets];
        var tally = new int[2];
        var thresholdNanos = threshold.toNanos();
        view.forEach(filter.scope(), specimen -> {
            if (filter.matches(specimen)) {
                tally[0]++;
                tally[1] += specimen.durationNanos() >= thresholdNanos ? 1 : 0;
                counts[DurationHistogram.bucketOf(specimen.durationNanos() / 1_000_000.0, buckets)]++;
            }
        });
        return new DurationHistogram(sequence, tally[0], tally[1], Arrays.stream(counts).boxed().toList());
    }

    // The page holds the first traces after the cursor in the order asked, by their value and then their root, both descending; only those are
    // built. A trace that would not make the page is passed over before anything is allocated for it.
    TracePage traces(TraceQuery query) {
        var after = query.after();
        // The last trace of the page at its head.
        var page = new PriorityQueue<Candidate>(query.limit() + 1);
        var tally = new int[3];
        view.forEachTrace(trace -> {
            if (!touches(trace, query.filter().scope())) {
                return;
            }
            tally[0]++;
            if (!matches(query, trace)) {
                return;
            }
            tally[1]++;
            var value = valueOf(query.order(), trace);
            var root = trace.root();
            if (after.isPresent() && !below(value, root, after.get().value(), after.get().root().value())) {
                return;
            }
            tally[2]++;
            if (page.size() == query.limit()) {
                var last = page.peek();
                if (below(value, root, last.value, last.root)) {
                    return;
                }
                page.poll();
            }
            page.add(new Candidate(value, root, trace));
        });
        var ordered = new ArrayList<>(page);
        ordered.sort(Comparator.reverseOrder());
        var traces = ordered.stream().map(candidate -> trace(candidate.trace)).flatMap(Optional::stream).toList();
        var next = tally[2] > ordered.size() && !ordered.isEmpty()
            ? Optional.of(new TraceCursor(ordered.getLast().value, new InvocationId(ordered.getLast().root)))
            : Optional.<TraceCursor>empty();
        return new TracePage(sequence, traces, tally[0], tally[1], next);
    }

    // Whether the trace of the value and root sorts below the other: after it in the order of a page.
    private static boolean below(long value, long root, long otherValue, long otherRoot) {
        return value < otherValue || value == otherValue && root < otherRoot;
    }

    Optional<TraceDetail> trace(InvocationId member) {
        return view.traceOf(member.value()).flatMap(snapshot -> trace(snapshot).map(trace -> {
            var members = new ArrayList<RetainedInvocation>(snapshot.size());
            for (var index = 0; index < snapshot.size(); index++) {
                view.specimen(snapshot.member(index)).map(this::retained).ifPresent(members::add);
            }
            return new TraceDetail(sequence, trace, members);
        }));
    }

    // The retained occurrences of the asked failure causes, in the order asked (F1).
    Failures failures(List<FailureCause.Step> asked) {
        var chains = failureChains();
        return new Failures(sequence, asked.stream().distinct().map(chains::cause).toList());
    }

    // The first failures query of the epoch walks its failed specimens, and every other reads what it found: each would otherwise walk every
    // failed specimen of the hub. The specimens never change, so neither does what it found; it lives as long as the epoch.
    private FailureChains failureChains() {
        var chains = failureChains;
        if (chains == null) {
            synchronized (this) {
                chains = failureChains;
                if (chains == null) {
                    chains = FailureChains.of(view);
                    failureChains = chains;
                }
            }
        }
        return chains;
    }

    TimelineWindow timeline(TimelineQuery query, Instant now) {
        var nowNanos = nanos(now);
        var fromNanos = nanos(now.minus(query.span()));
        var minNanos = query.minDuration().toNanos();
        var tally = new int[2];
        // The latest that matched, kept to the limit as they are found: the head is the earliest of them.
        var newest = Comparator.comparingLong(Specimen::startedAtNanos).thenComparingLong(Specimen::id).reversed();
        var latest = new PriorityQueue<>(newest.reversed());
        // Newest first, so once the limit is reached most of the rest started earlier than every one kept and is passed over at once.
        view.forEachNewestCompletedFrom(fromNanos, query.filter().scope(), specimen -> {
            if (specimen.handling() || specimen.startedAtNanos() > nowNanos || specimen.completedAtNanos() < fromNanos) {
                return true;
            }
            tally[0]++;
            if (query.filter().matches(specimen) && specimen.durationNanos() >= minNanos) {
                tally[1]++;
                if (latest.size() < query.limit()) {
                    latest.add(specimen);
                } else if (newest.compare(specimen, latest.peek()) < 0) {
                    latest.poll();
                    latest.add(specimen);
                }
            }
            return true;
        });
        var marked = new ArrayList<>(latest);
        marked.sort(newest);
        var ids = new HashSet<Long>();
        marked.forEach(specimen -> ids.add(specimen.id()));
        var marks = marked.stream()
            .map(specimen -> new TimelineWindow.Mark(specimen.invocation(), new InvocationId(rootOf(specimen)), markedAncestor(specimen, ids)))
            .toList();
        return new TimelineWindow(sequence, now, tally[0], tally[1], marks);
    }

    Optional<MessageDetail> message(InvocationId id) {
        var found = view.specimen(id.value());
        if (found.isEmpty()) {
            return Optional.empty();
        }
        var specimen = found.get();
        var ancestors = new ArrayDeque<RetainedInvocation>();
        var seen = new HashSet<Long>(Set.of(specimen.id()));
        for (var parent = parentOf(specimen); parent.isPresent() && seen.add(parent.get().id()); parent = parentOf(parent.get())) {
            ancestors.addFirst(retained(parent.get()));
        }
        var children = children(specimen);
        var propagatedFrom = specimen.failed()
            ? children.stream().filter(Specimen::failed).max(Comparator.comparingLong(Specimen::completedAtNanos)).map(Specimen::invocation)
            : Optional.<Invocation>empty();
        var shown = children.subList(0, Math.min(children.size(), MessageDetail.MAX_CHILDREN)).stream().map(Specimen::invocation).toList();
        return Optional.of(new MessageDetail(sequence, retained(specimen), new InvocationId(rootOf(specimen)), List.copyOf(ancestors), shown, children.size(),
            propagatedFrom));
    }

    RetainedType type(ApplicationType type) {
        var dispatches = new ArrayList<Specimen>();
        view.forEach(Optional.of(type.application()), specimen -> {
            if (!specimen.handling() && specimen.type().equals(type.type())) {
                dispatches.add(specimen);
            }
        });
        var failureTypes = new LinkedHashMap<String, Integer>();
        dispatches.forEach(specimen -> specimen.failureType().ifPresent(failureType -> failureTypes.merge(failureType, 1, Integer::sum)));
        var counted = failureTypes.entrySet().stream()
            .sorted(comparingByValue(Collections.reverseOrder()))
            .map(entry -> new RetainedType.FailureTypeCount(entry.getKey(), entry.getValue()))
            .toList();
        var latest = dispatches.subList(Math.max(0, dispatches.size() - RetainedType.LATEST), dispatches.size()).reversed();
        return new RetainedType(sequence, type, dispatches.size(), counted, latest.stream().map(Specimen::invocation).toList());
    }

    // Newest first.
    List<Invocation> withMessageIdPrefix(Optional<ApplicationName> scope, String prefix, int limit) {
        var lowercase = prefix.toLowerCase(Locale.ROOT);
        var found = new ArrayList<Invocation>();
        view.forEachNewest(scope, specimen -> {
            if (specimen.messageId().value().toString().startsWith(lowercase)) {
                found.add(specimen.invocation());
            }
            return found.size() < limit;
        });
        return found;
    }

    private RetainedInvocation retained(Specimen specimen) {
        return new RetainedInvocation(specimen.invocation(), specimen.parent(), parentOf(specimen).isPresent());
    }

    private Optional<Specimen> parentOf(Specimen specimen) {
        return specimen.parent().flatMap(parent -> view.specimen(parent.parent().value()));
    }

    private long rootOf(Specimen specimen) {
        return view.traceOf(specimen.id()).map(TraceSnapshot::root).orElse(specimen.id());
    }

    // In the order the hub retained them.
    private List<Specimen> children(Specimen specimen) {
        var children = new ArrayList<Specimen>();
        view.traceOf(specimen.id()).ifPresent(trace -> {
            for (var index = 0; index < trace.size(); index++) {
                view.specimen(trace.member(index))
                    .filter(member -> member.parent().filter(parent -> parent.parent().value() == specimen.id()).isPresent())
                    .ifPresent(children::add);
            }
        });
        return children;
    }

    private Optional<InvocationId> markedAncestor(Specimen specimen, Set<Long> marked) {
        var seen = new HashSet<Long>(Set.of(specimen.id()));
        for (var parent = parentOf(specimen); parent.isPresent() && seen.add(parent.get().id()); parent = parentOf(parent.get())) {
            if (marked.contains(parent.get().id())) {
                return Optional.of(new InvocationId(parent.get().id()));
            }
        }
        return Optional.empty();
    }

    private Optional<Trace> trace(TraceSnapshot snapshot) {
        return view.specimen(snapshot.root()).map(root -> new Trace(root.invocation(), snapshot.size(), snapshot.handlings(), snapshot.failures(),
            Instant.ofEpochSecond(0, snapshot.endNanos()), snapshot.threads(), snapshot.applications(), snapshot.composition()));
    }

    private boolean matches(TraceQuery query, TraceSnapshot trace) {
        var filter = query.filter();
        if (!filter.statuses().isEmpty() && !filter.statuses().contains(trace.status())
            || query.chains() && trace.messages() < 2
            || !filter.applications().isEmpty() && trace.applications().stream().noneMatch(filter.applications()::contains)) {
            return false;
        }
        if (filter.types().everyType() && filter.search().isEmpty()) {
            return true;
        }
        var typed = filter.types().everyType();
        var found = filter.search().isEmpty();
        for (var index = 0; index < trace.size() && !(typed && found); index++) {
            var member = view.specimen(trace.member(index));
            if (member.isPresent()) {
                typed = typed || filter.types().matches(member.get());
                found = found || InvocationFilter.found(member.get(), filter.search());
            }
        }
        return typed && found;
    }

    private static boolean touches(TraceSnapshot trace, Optional<ApplicationName> scope) {
        return scope.isEmpty() || trace.applications().contains(scope.get());
    }

    private static long valueOf(TraceOrder order, TraceSnapshot trace) {
        return switch (order) {
            case NEWEST -> trace.root();
            case SLOWEST -> trace.durationNanos();
            case LARGEST -> trace.messages();
        };
    }

    private static long nanos(Instant instant) {
        return instant.getEpochSecond() * 1_000_000_000L + instant.getNano();
    }

    private record Candidate(long value, long root, TraceSnapshot trace) implements Comparable<Candidate> {

        @Override
        public int compareTo(Candidate other) {
            return below(value, root, other.value, other.root) ? -1 : below(other.value, other.root, value, root) ? 1 : 0;
        }
    }
}
