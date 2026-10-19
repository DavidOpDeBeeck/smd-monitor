package app.dodb.smd.monitor.hub;

import app.dodb.smd.api.event.delivery.EventDelivery;
import app.dodb.smd.api.message.MessageId;
import app.dodb.smd.monitor.hub.intake.SpanBatch;
import app.dodb.smd.monitor.hub.span.ApplicationName;
import app.dodb.smd.monitor.hub.span.InstanceName;
import app.dodb.smd.monitor.hub.span.MessageKind;
import app.dodb.smd.monitor.hub.span.MessageSpan;
import app.dodb.smd.monitor.hub.span.MessageType;
import app.dodb.smd.monitor.hub.span.Outcome;
import app.dodb.smd.monitor.hub.span.SessionId;
import app.dodb.smd.monitor.hub.span.SinkSend;
import app.dodb.smd.monitor.hub.span.SpanRef;
import app.dodb.smd.monitor.hub.span.Stage;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.function.LongToIntFunction;
import java.util.stream.IntStream;

import static java.util.Objects.requireNonNull;

// A deterministic synthetic load at a profile: the span reports the instances of every application export as the clock advances. The same
// profile, seed, and clock steps give the same reports, without sleeping or reading the system clock.
//
// Every whole second the clock passes starts traces worth the profile's spans per second, each at an entry command of a random application and
// instance, the popular commands more often. A command's dispatch covers its handler, which may send a query and publishes an event, which
// processing groups handle: synchronously, on a worker thread the publication awaits, or later through an event store on any instance of the
// application. A group handling may dispatch a further command. Some events continue in the next application: published there again with
// their message ID, or handled there through a shared event store. A share of command dispatches and handlings fails, failing the dispatches
// that wait for them. Each instance exports
// the spans that ended every five seconds, in batches of at most 512, shuffled, so children often arrive before their parents; like
// OpenTelemetry's batch span processor, an instance that holds a full batch of ended spans exports it in the next second instead of waiting.
final class LoadGenerator {

    // The defaults of OpenTelemetry's batch span processor.
    static final int MAX_EXPORT_BATCH = 512;
    static final int EXPORT_INTERVAL_SECONDS = 5;
    static final double FAILURE_RATE = 0.02;
    static final EventDelivery EVENT_STORE = new EventDelivery("event-store");

    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final long OVERHEAD_NANOS = 20_000;
    private static final int MAX_DEPTH = 2;
    private static final List<String> FAILURE_TYPES = List.of("java.lang.IllegalStateException", "java.lang.IllegalArgumentException",
        "java.util.concurrent.TimeoutException", "app.dodb.load.InsufficientFundsException");

    private final LoadProfile profile;
    private final Clock clock;
    private final SplittableRandom random;
    private final LongToIntFunction spansPerSecond;
    private final int firstApplication;
    private final List<Application> applications;
    private long exportedThrough;

    LoadGenerator(LoadProfile profile, long seed, Clock clock) {
        this(profile, seed, clock, ignored -> profile.spansPerSecond(), 0);
    }

    // The traces of each second start the spans the rate gives for that epoch second, instead of the profile's. The applications are numbered
    // from the first, so generators of disjoint ranges load one hub together, each on its own thread.
    LoadGenerator(LoadProfile profile, long seed, Clock clock, LongToIntFunction spansPerSecond, int firstApplication) {
        this.profile = requireNonNull(profile);
        this.clock = requireNonNull(clock);
        this.spansPerSecond = requireNonNull(spansPerSecond);
        this.firstApplication = firstApplication;
        this.random = new SplittableRandom(seed);
        this.applications = IntStream.range(0, profile.applications()).mapToObj(Application::new).toList();
        this.exportedThrough = clock.instant().getEpochSecond();
    }

    LoadProfile profile() {
        return profile;
    }

    // The reports exported in the seconds the clock passed since the previous call, after starting the traces of each second that ended.
    List<SpanBatch> export() {
        var now = clock.instant().getEpochSecond();
        var reports = new ArrayList<SpanBatch>();
        for (var second = exportedThrough + 1; second <= now; second++) {
            startTraces(second - 1);
            for (var application : applications) {
                for (var instance : application.instances) {
                    reports.addAll(instance.exportsAt(second) ? instance.export(second) : instance.exportFull(second));
                }
            }
        }
        exportedThrough = Math.max(exportedThrough, now);
        Collections.shuffle(reports, random);
        return reports;
    }

    private void startTraces(long second) {
        var started = 0;
        var rate = spansPerSecond.applyAsInt(second);
        while (started < rate) {
            var application = applications.get(random.nextInt(applications.size()));
            var instance = application.instances.get(random.nextInt(application.instances.size()));
            var trace = new TraceBuilder();
            trace.dispatchCommand(application, instance, popular(application.commands.size()), Optional.empty(), second * NANOS_PER_SECOND
                + random.nextLong(NANOS_PER_SECOND), 0, "http-nio-8080-exec-" + (1 + random.nextInt(50)));
            started += trace.spans;
        }
    }

    // Low indexes more often: the first quarter takes half of the picks.
    private int popular(int size) {
        var u = random.nextDouble();
        return (int) (size * u * u);
    }

    // Mostly a fraction of a millisecond, with a long tail.
    private long work() {
        return Math.min((long) (Math.exp(random.nextGaussian()) * 200_000), 2 * NANOS_PER_SECOND);
    }

    // How long after a publication returned an event store delivers it: 20 ms and more, 300 ms on average, at most 8 s.
    private long storeDelay() {
        return Math.min(20_000_000 + (long) (random.nextExponential() * 300_000_000), 8 * NANOS_PER_SECOND);
    }

    private Optional<String> maybeFail() {
        return random.nextDouble() < FAILURE_RATE ? Optional.of(FAILURE_TYPES.get(random.nextInt(FAILURE_TYPES.size()))) : Optional.empty();
    }

    private static Instant instant(long epochNanos) {
        return Instant.ofEpochSecond(0, epochNanos);
    }

    private static Outcome outcome(long durationNanos, Optional<String> failure) {
        var duration = Duration.ofNanos(durationNanos);
        return failure.<Outcome>map(type -> new Outcome.Failed(duration, type)).orElseGet(() -> new Outcome.Completed(duration));
    }

    private static String hex(long value) {
        return HexFormat.of().toHexDigits(value);
    }

    // The message types and processing groups of one application. A group's delivery follows its index: every fourth group reads from the event
    // store, the one after it awaits a worker thread, and the others handle synchronously. Each event has one or two groups, every group has an
    // event when there are as many events as groups, and every group handles its events by the same delivery.
    private final class Application {

        private final int index;
        private final int number;
        private final ApplicationName name;
        private final List<Instance> instances;
        private final List<MessageType> commands;
        private final List<MessageType> events;
        private final List<MessageType> queries;
        private final List<String> groups;
        private final List<Integer> storeGroups;

        private Application(int index) {
            this.index = index;
            this.number = firstApplication + index;
            this.name = new ApplicationName("service-%02d".formatted(number));
            this.instances = IntStream.range(0, profile.instances()).mapToObj(instance -> new Instance(name, instance)).toList();
            var commandCount = profile.types() * 2 / 5;
            var eventCount = profile.types() * 2 / 5;
            this.commands = types(MessageKind.COMMAND, "Command", commandCount);
            this.events = types(MessageKind.EVENT, "Event", eventCount);
            this.queries = types(MessageKind.QUERY, "Query", profile.types() - commandCount - eventCount);
            this.groups = IntStream.range(0, profile.groups()).mapToObj(group -> "group-%03d".formatted(group)).toList();
            this.storeGroups = IntStream.range(0, profile.groups()).filter(group -> delivery(group).equals(EVENT_STORE)).boxed().toList();
        }

        private List<MessageType> types(MessageKind kind, String prefix, int count) {
            return IntStream.range(0, count).mapToObj(each -> new MessageType(kind, "app.dodb.load.service%02d.%s%03d".formatted(number, prefix, each))).toList();
        }

        private List<Integer> groupsOf(int event) {
            var first = event % groups.size();
            var second = (event * 7 + 3) % groups.size();
            return first == second ? List.of(first) : List.of(first, second);
        }

        private EventDelivery delivery(int group) {
            return switch (group % 4) {
                case 0 -> EVENT_STORE;
                case 1 -> EventDelivery.ASYNC_AWAIT;
                default -> EventDelivery.SYNC;
            };
        }

        private Instance anyInstance() {
            return instances.get(random.nextInt(instances.size()));
        }

        private Application next() {
            return applications.get((index + 1) % applications.size());
        }
    }

    private final class Instance {

        private final ApplicationName application;
        private final InstanceName name;
        private final SessionId session;
        private final int phase;
        private final List<Ended<MessageSpan>> spans = new ArrayList<>();
        private final List<Ended<SinkSend>> sends = new ArrayList<>();

        private Instance(ApplicationName application, int index) {
            this.application = application;
            this.name = new InstanceName(application.value() + "-%02d".formatted(index));
            this.session = new SessionId(new UUID(random.nextLong(), random.nextLong()).toString());
            this.phase = random.nextInt(EXPORT_INTERVAL_SECONDS);
        }

        private boolean exportsAt(long second) {
            return Math.floorMod(second + phase, EXPORT_INTERVAL_SECONDS) == 0;
        }

        // The spans and sends that ended before the second, shuffled, in reports of at most the maximum export batch.
        private List<SpanBatch> export(long second) {
            var until = second * NANOS_PER_SECOND;
            var readySpans = ended(spans, until);
            var readySends = ended(sends, until);
            var reports = new ArrayList<SpanBatch>();
            var spanIndex = 0;
            var sendIndex = 0;
            while (spanIndex < readySpans.size() || sendIndex < readySends.size()) {
                var spanCount = Math.min(MAX_EXPORT_BATCH, readySpans.size() - spanIndex);
                var sendCount = Math.min(MAX_EXPORT_BATCH - spanCount, readySends.size() - sendIndex);
                reports.add(new SpanBatch(application, name, session, List.copyOf(readySpans.subList(spanIndex, spanIndex + spanCount)),
                    List.copyOf(readySends.subList(sendIndex, sendIndex + sendCount))));
                spanIndex += spanCount;
                sendIndex += sendCount;
            }
            return reports;
        }

        // The full batches of the spans and sends that ended before the second; the rest wait for a later export.
        private List<SpanBatch> exportFull(long second) {
            var until = second * NANOS_PER_SECOND;
            if (endedCount(spans, until) + endedCount(sends, until) < MAX_EXPORT_BATCH) {
                return List.of();
            }
            var reports = export(second);
            var last = reports.getLast();
            if (last.size() < MAX_EXPORT_BATCH) {
                reports.removeLast();
                last.spans().forEach(span -> spans.add(new Ended<>(until, span)));
                last.sends().forEach(send -> sends.add(new Ended<>(until, send)));
            }
            return reports;
        }

        private static long endedCount(List<? extends Ended<?>> pending, long until) {
            return pending.stream().filter(each -> each.endNanos() <= until).count();
        }

        private <T> List<T> ended(List<Ended<T>> pending, long until) {
            var ready = new ArrayList<T>();
            pending.removeIf(each -> {
                if (each.endNanos() > until) {
                    return false;
                }
                ready.add(each.value());
                return true;
            });
            Collections.shuffle(ready, random);
            return ready;
        }
    }

    private record Ended<T>(long endNanos, T value) {

        private Ended {
            requireNonNull(value);
        }
    }

    // When an invocation ended and the failure it ended with.
    private record Done(long endNanos, Optional<String> failure) {

        private Done {
            requireNonNull(failure);
        }
    }

    private final class TraceBuilder {

        private final String traceId = hex(random.nextLong()) + hex(random.nextLong());
        private int spans;

        // A command's dispatch covers its handler: the handler's query and publication continue the dispatch.
        private Done dispatchCommand(Application application, Instance instance, int command, Optional<SpanRef> continues, long start, int depth, String thread) {
            var span = newSpan();
            var messageId = newMessageId();
            var cursor = start + OVERHEAD_NANOS + work();
            if (random.nextInt(4) == 0) {
                cursor = dispatchQuery(application, instance, command % application.queries.size(), span, cursor, thread) + work();
            }
            var event = command % application.events.size();
            var publication = publish(application, instance, application.events.get(event), event, newMessageId(), span, cursor, depth, thread, true);
            var end = publication.endNanos() + work() + OVERHEAD_NANOS;
            var failure = publication.failure().or(LoadGenerator.this::maybeFail);
            emit(instance, new MessageSpan(span, continues, messageId, new Stage.Dispatch(application.commands.get(command)), Optional.empty(), instant(start), thread,
                outcome(end - start, failure)), end);
            return new Done(end, failure);
        }

        private long dispatchQuery(Application application, Instance instance, int query, SpanRef sender, long start, String thread) {
            var end = start + OVERHEAD_NANOS + work() + OVERHEAD_NANOS;
            emit(instance, new MessageSpan(newSpan(), Optional.of(sender), newMessageId(), new Stage.Dispatch(application.queries.get(query)), Optional.empty(),
                instant(start), thread, outcome(end - start, Optional.empty())), end);
            return end;
        }

        // The groups of the event handle it within the publication, on a worker thread it awaits, or after it returned through the event store.
        // A publication continuing one of another application has its message ID, and is published there again.
        private Done publish(Application application, Instance instance, MessageType type, int event, MessageId messageId, SpanRef continues, long start,
                             int depth, String thread, boolean origin) {
            var span = newSpan();
            var cursor = start + OVERHEAD_NANOS;
            emit(instance, new SinkSend(newSpan(), type, EventDelivery.SYNC, instant(cursor)), cursor + OVERHEAD_NANOS);
            var awaited = cursor;
            var failure = Optional.<String>empty();
            var later = new ArrayList<Integer>();
            for (var group : application.groupsOf(event)) {
                var delivery = application.delivery(group);
                if (delivery.equals(EVENT_STORE)) {
                    later.add(group);
                } else if (delivery.equals(EventDelivery.ASYNC_AWAIT)) {
                    var handling = handleEvent(application, instance, group, type, span, messageId, cursor, depth, "smd-async-" + (1 + random.nextInt(8)));
                    awaited = Math.max(awaited, handling.endNanos());
                    failure = failure.or(handling::failure);
                } else {
                    var handling = handleEvent(application, instance, group, type, span, messageId, cursor, depth, thread);
                    cursor = handling.endNanos() + OVERHEAD_NANOS;
                    failure = failure.or(handling::failure);
                }
            }
            if (!later.isEmpty()) {
                emit(instance, new SinkSend(newSpan(), type, EVENT_STORE, instant(start + OVERHEAD_NANOS)), start + 2 * OVERHEAD_NANOS);
            }
            var end = Math.max(cursor, awaited) + OVERHEAD_NANOS;
            emit(instance, new MessageSpan(span, Optional.of(continues), messageId, new Stage.Dispatch(type), Optional.empty(), instant(start), thread,
                outcome(end - start, failure)), end);
            for (var group : later) {
                handleEvent(application, application.anyInstance(), group, type, span, messageId, end + storeDelay(), depth, "event-store-" + group);
            }
            if (origin && applications.size() > 1) {
                continueElsewhere(application.next(), type, event, messageId, span, end, depth);
            }
            return new Done(end, failure);
        }

        // Events 0, 4, 8, and so on are published again in the next application, and events 2, 6, 10, and so on are read from the event store there.
        private void continueElsewhere(Application next, MessageType type, int event, MessageId messageId, SpanRef publication, long end, int depth) {
            switch (event % 4) {
                case 0 -> publish(next, next.anyInstance(), type, event % next.events.size(), messageId, publication,
                    end + 1_000_000 + random.nextLong(50_000_000), depth + 1, "broker-consumer-1", false);
                case 2 -> {
                    var group = next.storeGroups.get(event % next.storeGroups.size());
                    handleEvent(next, next.anyInstance(), group, type, publication, messageId, end + storeDelay(), depth + 1, "event-store-" + group);
                }
                default -> {
                }
            }
        }

        private Done handleEvent(Application application, Instance instance, int group, MessageType type, SpanRef publication, MessageId messageId, long start,
                                 int depth, String thread) {
            var span = newSpan();
            var cursor = start + work();
            var failure = Optional.<String>empty();
            if (depth < MAX_DEPTH && group % 3 == 1) {
                var command = dispatchCommand(application, instance, (group * 7 + 1) % application.commands.size(), Optional.of(span), cursor, depth + 1,
                    thread);
                cursor = command.endNanos() + work();
                failure = command.failure();
            }
            failure = failure.or(LoadGenerator.this::maybeFail);
            emit(instance, new MessageSpan(span, Optional.of(publication), messageId, new Stage.Handling(type, application.groups.get(group)),
                Optional.of(application.delivery(group)), instant(start), thread, outcome(cursor - start, failure)), cursor);
            return new Done(cursor, failure);
        }

        private void emit(Instance instance, MessageSpan span, long end) {
            instance.spans.add(new Ended<>(end, span));
            spans++;
        }

        private void emit(Instance instance, SinkSend send, long end) {
            instance.sends.add(new Ended<>(end, send));
            spans++;
        }

        private SpanRef newSpan() {
            return SpanRef.of(traceId, hex(random.nextLong()));
        }

        private MessageId newMessageId() {
            return new MessageId(new UUID(random.nextLong(), random.nextLong()));
        }
    }
}
