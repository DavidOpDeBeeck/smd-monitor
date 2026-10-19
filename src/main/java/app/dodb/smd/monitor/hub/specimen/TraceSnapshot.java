package app.dodb.smd.monitor.hub.specimen;

import app.dodb.smd.monitor.hub.span.ApplicationName;
import app.dodb.smd.monitor.hub.span.MessageKind;
import app.dodb.smd.monitor.hub.span.Outcome;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static java.util.Objects.requireNonNull;

// A retained trace as an epoch shows it, which never changes: the IDs of its members in ascending sequence, its root, and its figures. The
// applications are in the order their members started; the composition counts the dispatches of each kind besides the root.
public final class TraceSnapshot {

    private final long root;
    private final long rootStartNanos;
    private final long[] members;
    private final int handlings;
    private final int failures;
    private final long endNanos;
    private final int threads;
    private final List<ApplicationName> applications;
    private final Map<MessageKind, Integer> composition;

    TraceSnapshot(long root, long rootStartNanos, long[] members, int handlings, int failures, long endNanos, int threads, List<ApplicationName> applications,
                  Map<MessageKind, Integer> composition) {
        this.root = root;
        this.rootStartNanos = rootStartNanos;
        this.members = requireNonNull(members);
        this.handlings = handlings;
        this.failures = failures;
        this.endNanos = endNanos;
        this.threads = threads;
        this.applications = requireNonNull(applications);
        this.composition = requireNonNull(composition);
    }

    public long root() {
        return root;
    }

    public long rootStartNanos() {
        return rootStartNanos;
    }

    public long durationNanos() {
        return endNanos - rootStartNanos;
    }

    public int size() {
        return members.length;
    }

    public long member(int index) {
        return members[index];
    }

    public boolean contains(long id) {
        return Arrays.binarySearch(members, id) >= 0;
    }

    public int handlings() {
        return handlings;
    }

    // The dispatched messages, leaving out the handlings.
    public int messages() {
        return members.length - handlings;
    }

    public int failures() {
        return failures;
    }

    public long endNanos() {
        return endNanos;
    }

    public int threads() {
        return threads;
    }

    public List<ApplicationName> applications() {
        return applications;
    }

    public Map<MessageKind, Integer> composition() {
        return composition;
    }

    public Outcome.Status status() {
        return failures > 0 ? Outcome.Status.FAILED : Outcome.Status.COMPLETED;
    }
}
