package app.dodb.smd.monitor.hub.web;

import app.dodb.smd.monitor.hub.ApplicationStatistics;
import app.dodb.smd.monitor.hub.ApplicationType;
import app.dodb.smd.monitor.hub.Epoch;
import app.dodb.smd.monitor.hub.InvocationFilter;
import app.dodb.smd.monitor.hub.InvocationId;
import app.dodb.smd.monitor.hub.MonitorHub;
import app.dodb.smd.monitor.hub.RaisedFailureStatistics;
import app.dodb.smd.monitor.hub.TapeQuery;
import app.dodb.smd.monitor.hub.TimelineQuery;
import app.dodb.smd.monitor.hub.TraceOrder;
import app.dodb.smd.monitor.hub.TraceQuery;
import app.dodb.smd.monitor.hub.TypeSelection;
import app.dodb.smd.monitor.hub.TypeStatistics;
import app.dodb.smd.monitor.hub.span.ApplicationName;
import app.dodb.smd.monitor.json.CausesView;
import app.dodb.smd.monitor.json.DurationsView;
import app.dodb.smd.monitor.json.FailureCausesView;
import app.dodb.smd.monitor.json.FlowView;
import app.dodb.smd.monitor.json.FoundView;
import app.dodb.smd.monitor.json.HandlingsView;
import app.dodb.smd.monitor.json.MessageDetailView;
import app.dodb.smd.monitor.json.QueryNames;
import app.dodb.smd.monitor.json.RetainedTypeView;
import app.dodb.smd.monitor.json.TapePageView;
import app.dodb.smd.monitor.json.TimelineWindowView;
import app.dodb.smd.monitor.json.TraceDetailView;
import app.dodb.smd.monitor.json.TracePageView;
import app.dodb.smd.monitor.json.TypeFiguresView;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;

// The retained invocations a page asks for, each answered from the latest epoch, from its parameters as sent: null when absent. A scope names
// one application; QueryNames reads the types, failure causes, kinds, statuses, and orders; durations are in milliseconds; a value the hub
// cannot read fails with an IllegalArgumentException naming it, which the server answers as QueryAnswer.invalid. Identical queries of one epoch
// are answered once (W5); beyond the queries running at once, the hub answers 429. Every answer but the timeline's carries an ETag of its content,
// so a page asking again for one that did not change receives 304 (QueryAnswer.unlessHeld).
public final class HubQueries {

    static final int DEFAULT_LIMIT = 100;
    // The hub's own mapper, so no other Jackson settings change the wire format the page reads.
    private final ObjectMapper mapper = new ObjectMapper();
    private final MonitorHub hub;
    private final QueryAnswers answers;

    // At most as many queries computed at once as pages may be open: a page waits for each answer before it asks again.
    public HubQueries(MonitorHub hub, int maxRunning) {
        this.hub = requireNonNull(hub);
        this.answers = new QueryAnswers(maxRunning);
    }

    public QueryAnswer tape(QueryFilter filter, String minDurationMillis, String after, String limit) {
        var query = new TapeQuery(filter(filter), minDuration(minDurationMillis), Optional.ofNullable(after).map(TapePageView::parseCursor), limit(limit));
        return answer(List.of("tape", query), epoch -> versioned(TapePageView.from(epoch.session(), epoch.tape(query))));
    }

    public QueryAnswer durations(QueryFilter filter, String minDurationMillis, String buckets) {
        var selected = filter(filter);
        var threshold = minDuration(minDurationMillis);
        var count = number("buckets", required("buckets", buckets));
        return answer(List.of("durations", selected, threshold, count),
            epoch -> versioned(DurationsView.from(epoch.session(), epoch.durations(selected, threshold, count))));
    }

    // Chains when true; the order is newest when absent.
    public QueryAnswer traces(QueryFilter filter, String chains, String order, String after, String limit) {
        var query = new TraceQuery(filter(filter), Boolean.parseBoolean(chains), order == null ? TraceOrder.NEWEST : QueryNames.order(order),
            Optional.ofNullable(after).map(TracePageView::parseCursor), limit(limit));
        return answer(List.of("traces", query), epoch -> versioned(TracePageView.from(epoch.session(), epoch.traces(query))));
    }

    public QueryAnswer trace(String sequence) {
        var id = invocationId(sequence);
        return answer(List.of("trace", id), epoch -> epoch.trace(id)
            .map(detail -> versioned(TraceDetailView.from(epoch.session(), detail)))
            .orElseGet(QueryAnswer::notFound));
    }

    public QueryAnswer failures(List<String> causes) {
        var asked = causes.stream().map(QueryNames::failureCause).toList();
        return answer(List.of("failures", asked), epoch -> versioned(FailureCausesView.from(epoch.session(), epoch.failures(asked))));
    }

    public QueryAnswer timeline(QueryFilter filter, String minDurationMillis, String spanMillis, String limit) {
        var query = new TimelineQuery(filter(filter), minDuration(minDurationMillis), millis("spanMillis", required("spanMillis", spanMillis)),
            number("limit", required("limit", limit)));
        // The window ends at the hub's clock, so the answer changes every epoch: an ETag could never match, and would cost hashing the largest answer.
        return answer(List.of("timeline", query), epoch -> json(TimelineWindowView.from(epoch.session(), epoch.timeline(query))));
    }

    public QueryAnswer invocation(String sequence) {
        var id = invocationId(sequence);
        return answer(List.of("invocation", id), epoch -> epoch.message(id)
            .map(detail -> versioned(MessageDetailView.from(epoch.session(), detail)))
            .orElseGet(QueryAnswer::notFound));
    }

    public QueryAnswer invocations(String scope, String messageIdPrefix, String limit) {
        var application = Optional.ofNullable(scope).map(ApplicationName::new);
        var prefix = required("messageIdPrefix", messageIdPrefix);
        var count = number("limit", required("limit", limit));
        return answer(List.of("invocations", application, prefix, count),
            epoch -> versioned(FoundView.from(epoch.session(), epoch.withMessageIdPrefix(application, prefix, count))));
    }

    public QueryAnswer type(String type) {
        var selected = QueryNames.type(required("type", type));
        return answer(List.of("types", selected), epoch -> versioned(RetainedTypeView.from(epoch.session(), epoch.type(selected), epoch.history(selected),
            selected, epoch.statistics().applications())));
    }

    // The figures of the message types of every application, or of the one the scope names, of the kinds and types selected, in the order a view
    // shows them: rate, the most dispatches in the window first; failed, the highest failed share in the window; failures, the most failed over the
    // history; p50, p95, or p99, the slowest in the window; name. Ties go by the simple name, then the type. The limit bounds the types answered,
    // not the total selected.
    public QueryAnswer typeFigures(String scope, List<String> kinds, List<String> types, String sort, String limit) {
        var application = Optional.ofNullable(scope).map(ApplicationName::new);
        var selection = new TypeSelection(kinds.stream().map(QueryNames::kind).collect(Collectors.toSet()),
            types.stream().map(QueryNames::type).collect(Collectors.toSet()));
        var order = typeOrder(required("sort", sort));
        var count = ranked(limit);
        return answer(List.of("typeFigures", application, selection, sort, count), epoch -> {
            var selected = epoch.statistics().applications().stream()
                .filter(statistics -> application.isEmpty() || application.get().equals(statistics.application()))
                .flatMap(statistics -> statistics.types().stream()
                    .filter(type -> selection.everyType() || selection.kinds().contains(type.type().kind())
                        || selection.types().contains(new ApplicationType(statistics.application(), type.type())))
                    .map(type -> new RankedType(statistics.application(), type, order.rank().applyAsDouble(type))))
                .sorted(Comparator.comparingDouble(RankedType::rank).reversed()
                    .thenComparing(order.then())
                    .thenComparing(ranked -> simpleName(ranked.type().type().name()), String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(ranked -> ranked.application().value())
                    .thenComparing(ranked -> ranked.type().type().kind())
                    .thenComparing(ranked -> ranked.type().type().name()))
                .toList();
            return versioned(new TypeFiguresView(epoch.session().toString(), epoch.sequence(), selected.size(),
                selected.stream().limit(count).map(ranked -> TypeFiguresView.TypeFigureView.from(ranked.application(), ranked.type())).toList()));
        });
    }

    // The failure causes of every application, or of the one the scope names, ranked as a view shows them: recent, those that failed in the
    // window, the most first; ranked, those that failed over the history, the most in the window first, then over the history. The limit bounds the
    // causes answered, not the total counted.
    public QueryAnswer causes(String scope, String order, String limit) {
        var application = Optional.ofNullable(scope).map(ApplicationName::new);
        var recent = switch (required("order", order)) {
            case "recent" -> true;
            case "ranked" -> false;
            default -> throw new IllegalArgumentException("The query parameter order must be recent or ranked; got '" + order + "'");
        };
        var count = ranked(limit);
        return answer(List.of("causes", application, recent, count), epoch -> {
            record Counted(ApplicationStatistics application, RaisedFailureStatistics failure) {
            }
            var counted = epoch.statistics().applications().stream()
                .filter(statistics -> application.isEmpty() || application.get().equals(statistics.application()))
                .flatMap(statistics -> statistics.failures().stream().map(failure -> new Counted(statistics, failure)))
                .filter(each -> recent ? each.failure().window() > 0 : each.failure().history() > 0)
                .sorted(Comparator.comparingLong((Counted each) -> each.failure().window()).reversed()
                    .thenComparing(Comparator.comparingLong((Counted each) -> each.failure().history()).reversed())
                    .thenComparing(each -> each.application().application().value())
                    .thenComparing(each -> each.failure().failure().stage().type().name())
                    .thenComparing(each -> each.failure().failure().failureType()))
                .toList();
            return versioned(new CausesView(epoch.session().toString(), epoch.sequence(), counted.size(),
                counted.stream().limit(count).map(each -> CausesView.CauseView.from(each.application(), each.failure())).toList()));
        });
    }

    // What each processing group's handlings counted, in every application or in the one the scope names, for an expanded group, the failures
    // view, and the flow view.
    public QueryAnswer handlings(String scope) {
        var application = Optional.ofNullable(scope).map(ApplicationName::new);
        return answer(List.of("handlings", application), epoch -> versioned(HandlingsView.from(epoch.session(), epoch.sequence(), epoch.statistics().applications()
            .stream()
            .filter(statistics -> application.isEmpty() || application.get().equals(statistics.application()))
            .toList())));
    }

    // How messages flow within and between the applications, or within the one the scope names, for the flow view and the live lanes.
    public QueryAnswer flow(String scope) {
        var application = Optional.ofNullable(scope).map(ApplicationName::new);
        return answer(List.of("flow", application), epoch -> versioned(FlowView.from(epoch.session(), epoch.sequence(), epoch.statistics().applications().stream()
            .filter(statistics -> application.isEmpty() || application.get().equals(statistics.application()))
            .toList())));
    }

    // Answers from the latest epoch; queries never wait for the hub's writer, nor it for them.
    QueryAnswer answer(Object query, Function<Epoch, QueryAnswer> answer) {
        var epoch = hub.epoch();
        return answers.answer(epoch, query, () -> answer.apply(epoch));
    }

    private QueryAnswer json(Object view) {
        return QueryAnswer.json(mapper.writeValueAsString(view));
    }

    // An answer the page may keep and ask again for with its ETag: a hash of its content but the sequence it names, which moves whenever the hub
    // retains anything, so an answer asked again names the same ETag while what it holds did not change.
    private QueryAnswer versioned(Object view) {
        ObjectNode content = mapper.valueToTree(view);
        var body = mapper.writeValueAsString(content);
        content.remove("sequence");
        return QueryAnswer.versioned(body, hash(mapper.writeValueAsString(content)));
    }

    private static String hash(String version) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(version.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("The JVM offers no SHA-256 to name the version of an answer", failure);
        }
    }

    private record RankedType(ApplicationName application, TypeStatistics type, double rank) {
    }

    // What ranks a type first, the higher the earlier, and what breaks its ties before the name.
    private record TypeOrder(ToDoubleFunction<TypeStatistics> rank, Comparator<RankedType> then) {
    }

    private static TypeOrder typeOrder(String sort) {
        Comparator<RankedType> none = (a, b) -> 0;
        return switch (sort) {
            case "rate" -> new TypeOrder(type -> type.window().started(), Comparator.comparingLong((RankedType ranked) -> ranked.type().history().started()).reversed());
            case "failed" -> new TypeOrder(type -> type.window().started() == 0 ? -1 : (double) type.window().failed() / type.window().started(),
                Comparator.comparingLong((RankedType ranked) -> ranked.type().window().failed()).reversed());
            case "failures" -> new TypeOrder(type -> type.history().failed(), none);
            case "p50" -> new TypeOrder(type -> nanos(type.window().latency().percentile(0.5)), none);
            case "p95" -> new TypeOrder(type -> nanos(type.window().latency().percentile(0.95)), none);
            case "p99" -> new TypeOrder(type -> nanos(type.window().latency().percentile(0.99)), none);
            case "name" -> new TypeOrder(type -> 0, none);
            default -> throw new IllegalArgumentException("The query parameter sort must be rate, failed, failures, p50, p95, p99, or name; got '" + sort + "'");
        };
    }

    // Nothing completed ranks below any latency.
    private static double nanos(Optional<Duration> latency) {
        return latency.map(Duration::toNanos).orElse(-1L);
    }

    // As the page names a type: the last part of its class name.
    private static String simpleName(String name) {
        return name.substring(Math.max(name.lastIndexOf('.'), name.lastIndexOf('$')) + 1);
    }

    private static InvocationFilter filter(QueryFilter filter) {
        return new InvocationFilter(Optional.ofNullable(filter.scope()).map(ApplicationName::new),
            filter.applications().stream().map(ApplicationName::new).collect(Collectors.toSet()),
            new TypeSelection(filter.kinds().stream().map(QueryNames::kind).collect(Collectors.toSet()),
                filter.types().stream().map(QueryNames::type).collect(Collectors.toSet())),
            filter.statuses().stream().map(QueryNames::status).collect(Collectors.toSet()),
            filter.search() == null ? "" : filter.search());
    }

    private static String required(String name, String value) {
        if (value == null) {
            throw new IllegalArgumentException("The query parameter " + name + " is required");
        }
        return value;
    }

    private static int number(String name, String value) {
        try {
            return Integer.parseInt(value.strip());
        } catch (NumberFormatException unreadable) {
            throw new IllegalArgumentException("The query parameter " + name + " must be a whole number; got '" + value + "'", unreadable);
        }
    }

    private static int limit(String limit) {
        return limit == null ? DEFAULT_LIMIT : number("limit", limit);
    }

    // How many of a ranking to answer; a view asks for more as it shows more, so only the least is bounded.
    private static int ranked(String limit) {
        var count = limit(limit);
        if (count < 1) {
            throw new IllegalArgumentException("The query parameter limit must be at least 1; got '" + limit + "'");
        }
        return count;
    }

    private static Duration minDuration(String minDurationMillis) {
        return minDurationMillis == null ? Duration.ZERO : millis("minDurationMillis", minDurationMillis);
    }

    private static Duration millis(String name, String value) {
        double millis;
        try {
            millis = Double.parseDouble(value.strip());
        } catch (NumberFormatException unreadable) {
            millis = Double.NaN;
        }
        if (!Double.isFinite(millis) || millis < 0) {
            throw new IllegalArgumentException("The query parameter " + name + " must be a number of milliseconds of at least 0; got '" + value + "'");
        }
        return Duration.ofNanos(Math.round(millis * 1_000_000));
    }

    private static InvocationId invocationId(String sequence) {
        try {
            return new InvocationId(Long.parseLong(sequence));
        } catch (NumberFormatException unreadable) {
            throw new IllegalArgumentException("An invocation or a trace is named by its sequence number; got '" + sequence + "'", unreadable);
        }
    }
}
