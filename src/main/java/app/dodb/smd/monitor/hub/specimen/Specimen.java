package app.dodb.smd.monitor.hub.specimen;

import app.dodb.smd.api.event.delivery.EventDelivery;
import app.dodb.smd.api.message.MessageId;
import app.dodb.smd.monitor.hub.ApplicationType;
import app.dodb.smd.monitor.hub.Invocation;
import app.dodb.smd.monitor.hub.InvocationId;
import app.dodb.smd.monitor.hub.ParentSummary;
import app.dodb.smd.monitor.hub.join.SpanKey;
import app.dodb.smd.monitor.hub.span.ApplicationName;
import app.dodb.smd.monitor.hub.span.InstanceName;
import app.dodb.smd.monitor.hub.span.MessageSpan;
import app.dodb.smd.monitor.hub.span.MessageType;
import app.dodb.smd.monitor.hub.span.Outcome;
import app.dodb.smd.monitor.hub.span.SpanRef;
import app.dodb.smd.monitor.hub.span.Stage;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

// A retained invocation, compact (M6): its IDs and times as numbers, and its names shared with the other specimens of its application. It never
// changes; the parent summary found later replaces the specimen with a copy. The queries rebuild an Invocation only for what they answer.
public final class Specimen {

    // The heap a specimen takes with its slot in a chunk and its share of its trace, as a constant.
    public static final long BYTES = 160;
    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private final long id;
    private final ApplicationName application;
    private final InstanceName instance;
    private final Stage stage;
    // Absent for all but a processing group's handling.
    private final EventDelivery delivery;
    private final String threadName;
    // Absent unless it failed.
    private final String failureType;
    private final long traceHigh;
    private final long traceLow;
    private final long spanId;
    private final boolean continues;
    private final long parentTraceHigh;
    private final long parentTraceLow;
    private final long parentSpanId;
    private final long messageHigh;
    private final long messageLow;
    private final long startedAtNanos;
    private final long durationNanos;
    // Absent until the hub found the parent.
    private final ParentSummary parent;

    private Specimen(long id, ApplicationName application, InstanceName instance, Stage stage, EventDelivery delivery, String threadName, String failureType,
                     long traceHigh, long traceLow, long spanId, boolean continues, long parentTraceHigh, long parentTraceLow, long parentSpanId,
                     long messageHigh, long messageLow, long startedAtNanos, long durationNanos, ParentSummary parent) {
        this.id = id;
        this.application = requireNonNull(application);
        this.instance = requireNonNull(instance);
        this.stage = requireNonNull(stage);
        this.delivery = delivery;
        this.threadName = requireNonNull(threadName);
        this.failureType = failureType;
        this.traceHigh = traceHigh;
        this.traceLow = traceLow;
        this.spanId = spanId;
        this.continues = continues;
        this.parentTraceHigh = parentTraceHigh;
        this.parentTraceLow = parentTraceLow;
        this.parentSpanId = parentSpanId;
        this.messageHigh = messageHigh;
        this.messageLow = messageLow;
        this.startedAtNanos = startedAtNanos;
        this.durationNanos = durationNanos;
        this.parent = parent;
    }

    static Specimen of(InvocationId id, ApplicationName application, InstanceName instance, MessageSpan span, Names names) {
        var key = SpanKey.of(span.span());
        var parentKey = span.continues().map(SpanKey::of);
        var message = span.messageId().value();
        var failureType = span.outcome() instanceof Outcome.Failed(var ignored, var type) ? names.intern(type) : null;
        return new Specimen(id.value(), application, names.intern(instance), names.intern(span.stage()), span.delivery().map(names::intern).orElse(null),
            names.intern(span.threadName()), failureType, key.traceHigh(), key.traceLow(), key.spanId(), parentKey.isPresent(),
            parentKey.map(SpanKey::traceHigh).orElse(0L), parentKey.map(SpanKey::traceLow).orElse(0L), parentKey.map(SpanKey::spanId).orElse(0L),
            message.getMostSignificantBits(), message.getLeastSignificantBits(), nanos(span.startedAt()), span.outcome().duration().toNanos(), null);
    }

    Specimen withParent(ParentSummary parent) {
        return new Specimen(id, application, instance, stage, delivery, threadName, failureType, traceHigh, traceLow, spanId, continues, parentTraceHigh,
            parentTraceLow, parentSpanId, messageHigh, messageLow, startedAtNanos, durationNanos, requireNonNull(parent));
    }

    public long id() {
        return id;
    }

    public ApplicationName application() {
        return application;
    }

    public InstanceName instance() {
        return instance;
    }

    public Stage stage() {
        return stage;
    }

    public MessageType type() {
        return stage.type();
    }

    public ApplicationType applicationType() {
        return new ApplicationType(application, stage.type());
    }

    public boolean handling() {
        return stage instanceof Stage.Handling;
    }

    public Optional<String> processingGroup() {
        return stage instanceof Stage.Handling handling ? handling.processingGroup() : Optional.empty();
    }

    public String threadName() {
        return threadName;
    }

    public boolean failed() {
        return failureType != null;
    }

    public Optional<String> failureType() {
        return Optional.ofNullable(failureType);
    }

    public Outcome.Status status() {
        return failed() ? Outcome.Status.FAILED : Outcome.Status.COMPLETED;
    }

    public long startedAtNanos() {
        return startedAtNanos;
    }

    public long durationNanos() {
        return durationNanos;
    }

    public long completedAtNanos() {
        return startedAtNanos + durationNanos;
    }

    public Instant startedAt() {
        return instant(startedAtNanos);
    }

    public Instant completedAt() {
        return instant(completedAtNanos());
    }

    public Duration duration() {
        return Duration.ofNanos(durationNanos);
    }

    public MessageId messageId() {
        return new MessageId(new UUID(messageHigh, messageLow));
    }

    // Whether its span links to a parent, found or not.
    public boolean continues() {
        return continues;
    }

    public Optional<ParentSummary> parent() {
        return Optional.ofNullable(parent);
    }

    SpanKey key() {
        return new SpanKey(traceHigh, traceLow, spanId);
    }

    public Invocation invocation() {
        var outcome = failureType == null ? new Outcome.Completed(duration()) : new Outcome.Failed(duration(), failureType);
        var continued = continues ? Optional.of(new SpanRef(parentTraceHigh, parentTraceLow, parentSpanId)) : Optional.<SpanRef>empty();
        var span = new MessageSpan(new SpanRef(traceHigh, traceLow, spanId), continued, messageId(), stage, Optional.ofNullable(delivery), startedAt(), threadName,
            outcome);
        return new Invocation(new InvocationId(id), application, instance, span);
    }

    private static long nanos(Instant instant) {
        return instant.getEpochSecond() * NANOS_PER_SECOND + instant.getNano();
    }

    private static Instant instant(long nanos) {
        return Instant.ofEpochSecond(Math.floorDiv(nanos, NANOS_PER_SECOND), Math.floorMod(nanos, NANOS_PER_SECOND));
    }
}
