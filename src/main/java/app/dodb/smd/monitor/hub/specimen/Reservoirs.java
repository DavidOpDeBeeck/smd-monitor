package app.dodb.smd.monitor.hub.specimen;

import app.dodb.smd.monitor.hub.ApplicationType;
import app.dodb.smd.monitor.hub.FailureCause;
import app.dodb.smd.monitor.hub.span.ApplicationName;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

// The reservoirs that judge a trace when Recent lets it go (M4, M5): Slowest keeps the K slowest dispatches of each type that started within
// the window, with their traces; Failures keeps the latest K traces of each deepest failure. An entry holds its trace; a trace no entry holds
// and Recent let go is dropped. Each application has its own K, 5 for Slowest and 20 for Failures, which memory pressure lowers one at a time,
// Slowest first and Failures never below 1, and which grow back in the opposite order. The entries are kept by application, so lowering one
// application's K touches only its own types and causes. Owned by the writer.
final class Reservoirs {

    static final int SLOWEST = 5;
    static final int FAILURES = 20;

    private final Map<ApplicationName, Map<ApplicationType, List<Slow>>> slowest = new HashMap<>();
    private final Map<ApplicationName, Map<FailureCause.Step, ArrayDeque<Kept>>> failures = new HashMap<>();
    private final Map<ApplicationName, Limits> limits = new HashMap<>();

    // Offers the trace and its dispatches, and returns the traces whose entries it displaced.
    List<TraceComponent> judge(TraceComponent trace, List<Specimen> members, Optional<FailureCause.Step> deepestFailure, long windowStartNanos) {
        var displaced = new ArrayList<TraceComponent>();
        deepestFailure.ifPresent(cause -> {
            var latest = failures.computeIfAbsent(cause.application(), ignored -> new HashMap<>()).computeIfAbsent(cause, ignored -> new ArrayDeque<>());
            if (latest.stream().noneMatch(kept -> kept.trace.find() == trace)) {
                latest.addLast(new Kept(trace));
                trace.reservoirEntries++;
                while (latest.size() > limitsOf(cause.application()).failures) {
                    displaced.add(leave(latest.pollFirst()));
                }
            }
        });
        for (var member : members) {
            var type = member.applicationType();
            var k = limitsOf(type.application()).slowest;
            if (member.handling() || member.startedAtNanos() < windowStartNanos || k == 0) {
                continue;
            }
            var kept = slowest.computeIfAbsent(type.application(), ignored -> new HashMap<>()).computeIfAbsent(type, ignored -> new ArrayList<>());
            if (kept.stream().anyMatch(slow -> slow.specimen == member.id())) {
                continue;
            }
            if (kept.size() >= k) {
                var fastest = fastest(kept);
                if (member.durationNanos() <= fastest.durationNanos) {
                    continue;
                }
                kept.remove(fastest);
                displaced.add(leave(fastest));
            }
            kept.add(new Slow(member.id(), member.durationNanos(), member.startedAtNanos(), trace));
            trace.reservoirEntries++;
        }
        return displaced;
    }

    // The dispatches that started before the window leave Slowest; returns the traces they held.
    List<TraceComponent> expire(long windowStartNanos) {
        var displaced = new ArrayList<TraceComponent>();
        for (var types : slowest.values()) {
            for (var kept : types.values()) {
                kept.removeIf(slow -> {
                    if (slow.startNanos >= windowStartNanos) {
                        return false;
                    }
                    displaced.add(leave(slow));
                    return true;
                });
            }
            types.values().removeIf(List::isEmpty);
        }
        slowest.values().removeIf(Map::isEmpty);
        return displaced;
    }

    // Lowers the application's K by one, Slowest before Failures, and returns the traces of the entries beyond it; empty when both reached their
    // floor.
    Optional<List<TraceComponent>> shrink(ApplicationName application) {
        var limits = limitsFor(application);
        var displaced = new ArrayList<TraceComponent>();
        if (limits.slowest > 0) {
            limits.slowest--;
            var types = slowest.getOrDefault(application, Map.of());
            for (var kept : types.values()) {
                while (kept.size() > limits.slowest) {
                    var fastest = fastest(kept);
                    kept.remove(fastest);
                    displaced.add(leave(fastest));
                }
            }
            types.values().removeIf(List::isEmpty);
            if (types.isEmpty()) {
                slowest.remove(application);
            }
            return Optional.of(displaced);
        }
        if (limits.failures > 1) {
            limits.failures--;
            for (var latest : failures.getOrDefault(application, Map.of()).values()) {
                while (latest.size() > limits.failures) {
                    displaced.add(leave(latest.pollFirst()));
                }
            }
            return Optional.of(displaced);
        }
        return Optional.empty();
    }

    // Raises the application's K by one, Failures before Slowest; false when both are whole.
    boolean grow(ApplicationName application) {
        var limits = this.limits.get(application);
        if (limits == null) {
            return false;
        }
        if (limits.failures < FAILURES) {
            limits.failures++;
            return true;
        }
        if (limits.slowest < SLOWEST) {
            limits.slowest++;
            return true;
        }
        this.limits.remove(application);
        return false;
    }

    int slowestK(ApplicationName application) {
        return limitsOf(application).slowest;
    }

    int failuresK(ApplicationName application) {
        return limitsOf(application).failures;
    }

    // The entries of a trace dropped for another reason, such as its application retiring.
    void forget(TraceComponent trace) {
        for (var types : slowest.values()) {
            for (var kept : types.values()) {
                kept.removeIf(slow -> slow.trace.find() == trace);
            }
        }
        for (var causes : failures.values()) {
            for (var latest : causes.values()) {
                latest.removeIf(kept -> kept.trace.find() == trace);
            }
        }
    }

    void retire(ApplicationName application) {
        limits.remove(application);
        failures.remove(application);
        slowest.remove(application);
    }

    private Limits limitsOf(ApplicationName application) {
        var limits = this.limits.get(application);
        return limits == null ? Limits.WHOLE : limits;
    }

    private static Slow fastest(List<Slow> kept) {
        return kept.stream().min(Comparator.comparingLong(slow -> slow.durationNanos)).orElseThrow();
    }

    private static TraceComponent leave(Entry entry) {
        var current = entry.trace.find();
        current.reservoirEntries--;
        return current;
    }

    private Limits limitsFor(ApplicationName application) {
        return limits.computeIfAbsent(application, ignored -> new Limits(SLOWEST, FAILURES));
    }

    // The K of an application while memory pressure lowered it; an application without limits keeps whole reservoirs.
    private static final class Limits {

        private static final Limits WHOLE = new Limits(SLOWEST, FAILURES);

        private int slowest;
        private int failures;

        private Limits(int slowest, int failures) {
            this.slowest = slowest;
            this.failures = failures;
        }
    }

    private abstract static class Entry {

        final TraceComponent trace;

        Entry(TraceComponent trace) {
            this.trace = requireNonNull(trace);
        }
    }

    private static final class Slow extends Entry {

        private final long specimen;
        private final long durationNanos;
        private final long startNanos;

        private Slow(long specimen, long durationNanos, long startNanos, TraceComponent trace) {
            super(trace);
            this.specimen = specimen;
            this.durationNanos = durationNanos;
            this.startNanos = startNanos;
        }
    }

    private static final class Kept extends Entry {

        private Kept(TraceComponent trace) {
            super(trace);
        }
    }
}
