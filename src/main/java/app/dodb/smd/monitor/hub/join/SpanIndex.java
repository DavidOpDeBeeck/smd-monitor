package app.dodb.smd.monitor.hub.join;

import app.dodb.smd.monitor.hub.budget.FairShare;
import app.dodb.smd.monitor.hub.budget.Footprint;
import app.dodb.smd.monitor.hub.span.ApplicationName;
import app.dodb.smd.monitor.hub.span.SpanRef;
import app.dodb.smd.monitor.hub.span.Stage;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;

import static java.util.Objects.requireNonNull;

// One index of the spans and sends of every application the hub received, owned by its writer (J1). An entry lives while it is within the
// link horizon or while the hub retains its invocation (J2). A span or send the index holds arrives again as a duplicate and is not received
// again (J3). A link is found when the second of a child and its parent arrives, in either order, once per child (J4). A child waits for its
// parent within the horizon, or while retained; one that leaves unlinked is counted for its application by the reason (J5). What the index
// keeps beyond retention is the join state, which the hub fits within its allowance: the application furthest above its fair share loses its
// oldest entries first, which shortens its effective horizon (M2, M3). A retained entry is accounted with its specimen.
public final class SpanIndex {

    private final long horizonSeconds;
    private final Map<SpanKey, Entry> entries = new HashMap<>();
    // The children whose parent did not arrive yet, by the key of that parent.
    private final Map<SpanKey, List<Entry>> awaitingParent = new HashMap<>();
    // The parents of children that left unlinked, for another horizon, by the key of that parent.
    private final Map<SpanKey, Tombstone> tombstones = new HashMap<>();
    private final ArrayDeque<Tombstone> tombstoneOrder = new ArrayDeque<>();
    private final Map<ApplicationName, Received> applications = new HashMap<>();
    // The bytes of every application's entries outside retention and children awaiting their parent, kept with theirs, so fitting does not add
    // them up again for each entry it forgets.
    private long receivedBytes;

    public SpanIndex(Duration horizon) {
        this.horizonSeconds = requireNonNull(horizon).toSeconds();
    }

    public boolean knows(SpanRef span) {
        return entries.containsKey(SpanKey.of(span));
    }

    // Indexes a span, which the hub may retain as a specimen, and returns the links found: to its parent, then from the children that awaited
    // it.
    public List<Link> receive(SpanRef span, Optional<SpanRef> continues, Fact fact, Instant now, boolean retained) {
        var key = SpanKey.of(span);
        var parentKey = continues.map(SpanKey::of).orElse(null);
        var entry = add(new Entry(key, fact.application(), fact, parentKey, now.getEpochSecond()), retained);
        var found = new ArrayList<Link>();
        if (parentKey != null) {
            var parent = entries.get(parentKey);
            if (parent == null || parent.fact == null) {
                awaitingParent.computeIfAbsent(parentKey, ignored -> new ArrayList<>()).add(entry);
                entry.awaiting = true;
                account(received(entry.application), Footprint.AWAITING);
            } else {
                found.add(link(fact, parent.fact));
            }
        }
        var children = awaitingParent.remove(key);
        if (children != null) {
            for (var child : children) {
                child.awaiting = false;
                account(received(child.application), -Footprint.AWAITING);
                found.add(link(child.fact, fact));
            }
        }
        var tombstone = tombstones.remove(key);
        if (tombstone != null) {
            tombstone.children.forEach((application, count) -> received(application).parentAfterHorizon += count);
        }
        return found;
    }

    // Indexes a send, which no span continues, so a send that arrives again counts once.
    public void receiveSend(SpanRef span, ApplicationName application, Instant now) {
        add(new Entry(SpanKey.of(span), application, null, null, now.getEpochSecond()), false);
    }

    // The hub no longer retains the invocation of the span; its entry stays while within the horizon.
    public void release(SpanRef span) {
        release(SpanKey.of(span));
    }

    public void release(SpanKey key) {
        var entry = entries.get(key);
        if (entry == null || !entry.retained) {
            return;
        }
        // An entry that left the horizon while retained goes now, without ever counting as join state.
        if (entry.expired) {
            remove(entry);
            entry.retained = false;
        } else {
            entry.retained = false;
            account(received(entry.application), entry.bytes());
        }
    }

    // Forgets the entries that left the horizon and are not retained, and counts the children whose parent never arrived. An effective horizon
    // grows back a second at a time, as the entries kept age.
    public void expire(Instant now) {
        var oldest = now.getEpochSecond() - horizonSeconds;
        for (var application : applications.values()) {
            while (!application.arrivals.isEmpty() && application.arrivals.peekFirst().receivedSecond < oldest) {
                leaveHorizon(application.arrivals.pollFirst());
            }
        }
        while (!tombstoneOrder.isEmpty() && tombstoneOrder.peekFirst().expiresSecond < now.getEpochSecond()) {
            var tombstone = tombstoneOrder.pollFirst();
            if (tombstones.remove(tombstone.parent, tombstone)) {
                tombstone.children.forEach((application, count) -> received(application).parentNeverReceived += count);
            }
        }
        applications.values().forEach(application -> application.effectiveHorizon = Math.min(horizonSeconds, application.effectiveHorizon + 1));
    }

    // Lets go of the oldest entries of the application furthest above its fair share until the join state fits the allowance; they leave the
    // horizon early, which shortens that application's effective horizon. An application within its share keeps its entries (TR9). Once no
    // application is above its share, the oldest tombstones go, their children counted as never linked. False when the join state does not
    // fit even so.
    public boolean fit(long allowance, Instant now) {
        if (bytes() <= allowance) {
            return true;
        }
        // An entry leaving takes bytes only from its own application, and the share stays: one within its share stays within it.
        var share = FairShare.of(allowance, applications.size());
        var furthest = new PriorityQueue<Received>(Comparator.comparingLong((Received application) -> application.bytes).reversed());
        applications.values().forEach(application -> {
            if (aboveShare(application, share)) {
                furthest.add(application);
            }
        });
        while (bytes() > allowance) {
            var most = furthest.poll();
            if (most != null) {
                var oldest = most.arrivals.pollFirst();
                most.effectiveHorizon = Math.min(most.effectiveHorizon, Math.max(0, now.getEpochSecond() - oldest.receivedSecond));
                leaveHorizon(oldest);
                if (aboveShare(most, share)) {
                    furthest.add(most);
                }
            } else if (!tombstoneOrder.isEmpty()) {
                var tombstone = tombstoneOrder.pollFirst();
                if (tombstones.remove(tombstone.parent, tombstone)) {
                    tombstone.children.forEach((application, count) -> received(application).parentNeverReceived += count);
                }
            } else {
                return false;
            }
        }
        return true;
    }

    // The join state: the entries outside retention and the children awaiting their parent of every application, and the tombstones.
    public long bytes() {
        return receivedBytes + tombstones.size() * Footprint.TOMBSTONE;
    }

    // Forgets every entry of a retired application.
    public void forget(ApplicationName application) {
        var forgotten = applications.remove(application);
        if (forgotten == null) {
            return;
        }
        receivedBytes -= forgotten.bytes;
        // The hub released every retained invocation of the application first, so only its arrivals remain; their children waiting on a parent
        // go without a trace, as their application does.
        for (var entry : forgotten.arrivals) {
            entries.remove(entry.key, entry);
            if (entry.awaiting) {
                var waiting = awaitingParent.get(entry.parent);
                if (waiting != null && waiting.remove(entry) && waiting.isEmpty()) {
                    awaitingParent.remove(entry.parent);
                }
            }
        }
    }

    public int entries(ApplicationName application) {
        var received = applications.get(application);
        return received == null ? 0 : received.size;
    }

    // The bytes of the application's entries outside retention and of its children awaiting their parent.
    public long bytes(ApplicationName application) {
        var received = applications.get(application);
        return received == null ? 0 : received.bytes;
    }

    // How long the application's entries stay: the horizon, or less while the join state did not fit its allowance.
    public Duration effectiveHorizon(ApplicationName application) {
        var received = applications.get(application);
        return Duration.ofSeconds(received == null ? horizonSeconds : received.effectiveHorizon);
    }

    public Unlinked unlinked(ApplicationName application) {
        var received = applications.get(application);
        return received == null ? Unlinked.NONE : new Unlinked(received.parentNeverReceived, received.parentAfterHorizon);
    }

    private Entry add(Entry entry, boolean retained) {
        entries.put(entry.key, entry);
        var application = received(entry.application);
        application.arrivals.addLast(entry);
        application.size++;
        entry.retained = retained;
        if (!retained) {
            account(application, entry.bytes());
        }
        return entry;
    }

    private void leaveHorizon(Entry entry) {
        entry.expired = true;
        if (!entry.retained) {
            remove(entry);
        }
    }

    private void remove(Entry entry) {
        removeFromIndex(entry);
        var application = applications.get(entry.application);
        if (application != null) {
            application.size--;
            account(application, entry.retained ? 0 : -entry.bytes());
        }
    }

    private void removeFromIndex(Entry entry) {
        entries.remove(entry.key, entry);
        if (entry.awaiting) {
            entry.awaiting = false;
            var application = applications.get(entry.application);
            if (application != null) {
                account(application, -Footprint.AWAITING);
            }
            var waiting = awaitingParent.get(entry.parent);
            if (waiting != null && waiting.remove(entry) && waiting.isEmpty()) {
                awaitingParent.remove(entry.parent);
            }
            var tombstone = tombstones.computeIfAbsent(entry.parent, parent -> {
                var created = new Tombstone(parent, entry.receivedSecond + 2 * horizonSeconds);
                tombstoneOrder.addLast(created);
                return created;
            });
            tombstone.children.merge(entry.application, 1L, Long::sum);
        }
    }

    private void account(Received application, long bytes) {
        application.bytes += bytes;
        receivedBytes += bytes;
    }

    private static boolean aboveShare(Received application, long share) {
        return !application.arrivals.isEmpty() && application.bytes > share;
    }

    private Received received(ApplicationName application) {
        return applications.computeIfAbsent(application, ignored -> new Received(horizonSeconds));
    }

    private static Link link(Fact child, Fact parent) {
        var delivered = child.stage() instanceof Stage.Dispatch && parent.stage() instanceof Stage.Dispatch && parent.messageId().equals(child.messageId());
        return new Link(child, parent, delivered);
    }

    private static final class Entry {

        private final SpanKey key;
        private final ApplicationName application;
        // Absent for a send, which only counts once.
        private final Fact fact;
        private final SpanKey parent;
        private final long receivedSecond;
        private boolean retained;
        private boolean expired;
        private boolean awaiting;

        private Entry(SpanKey key, ApplicationName application, Fact fact, SpanKey parent, long receivedSecond) {
            this.key = key;
            this.application = application;
            this.fact = fact;
            this.parent = parent;
            this.receivedSecond = receivedSecond;
        }

        private long bytes() {
            return fact == null ? Footprint.SEND : Footprint.FACT + (parent == null ? 0 : Footprint.PARENT_KEY);
        }
    }

    // The entries of an application in the order they arrived, the heap of those outside retention and of its children awaiting their parent,
    // how long they stay, and its children that left unlinked.
    private static final class Received {

        private final ArrayDeque<Entry> arrivals = new ArrayDeque<>();
        private int size;
        private long bytes;
        private long effectiveHorizon;
        private long parentNeverReceived;
        private long parentAfterHorizon;

        private Received(long horizonSeconds) {
            this.effectiveHorizon = horizonSeconds;
        }
    }

    private static final class Tombstone {

        private final SpanKey parent;
        private final long expiresSecond;
        private final Map<ApplicationName, Long> children = new HashMap<>();

        private Tombstone(SpanKey parent, long expiresSecond) {
            this.parent = parent;
            this.expiresSecond = expiresSecond;
        }
    }
}
