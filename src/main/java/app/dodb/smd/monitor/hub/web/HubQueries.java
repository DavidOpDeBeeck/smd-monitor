package app.dodb.smd.monitor.hub.web;

import app.dodb.smd.monitor.hub.Epoch;
import app.dodb.smd.monitor.hub.InvocationFilter;
import app.dodb.smd.monitor.hub.InvocationId;
import app.dodb.smd.monitor.hub.MonitorHub;
import app.dodb.smd.monitor.hub.TapeQuery;
import app.dodb.smd.monitor.hub.TimelineQuery;
import app.dodb.smd.monitor.hub.TraceOrder;
import app.dodb.smd.monitor.hub.TraceQuery;
import app.dodb.smd.monitor.hub.TypeSelection;
import app.dodb.smd.monitor.hub.span.ApplicationName;
import app.dodb.smd.monitor.json.DurationsView;
import app.dodb.smd.monitor.json.FailureCausesView;
import app.dodb.smd.monitor.json.FoundView;
import app.dodb.smd.monitor.json.MessageDetailView;
import app.dodb.smd.monitor.json.QueryNames;
import app.dodb.smd.monitor.json.RetainedTypeView;
import app.dodb.smd.monitor.json.TapePageView;
import app.dodb.smd.monitor.json.TimelineWindowView;
import app.dodb.smd.monitor.json.TraceDetailView;
import app.dodb.smd.monitor.json.TracePageView;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;

// The retained invocations a page asks for, each answered from the latest epoch, from its parameters as sent: null when absent. A scope names
// one application; QueryNames reads the types, failure causes, kinds, statuses, and orders; durations are in milliseconds; a value the hub
// cannot read fails with an IllegalArgumentException naming it, which the server answers as QueryAnswer.invalid. Identical queries of one epoch
// are answered once (W5); beyond the queries running at once, the hub answers 429. A trace and an invocation answer with an ETag of their
// content, so a page asking again for one that did not change receives 304.
public final class HubQueries {

    static final int DEFAULT_LIMIT = 100;
    // The hub's own mapper, so no other Jackson settings change the wire format the page reads.
    private final ObjectMapper mapper = new ObjectMapper();
    private final MonitorHub hub;
    private final QueryAnswers answers = new QueryAnswers();

    public HubQueries(MonitorHub hub) {
        this.hub = requireNonNull(hub);
    }

    public QueryAnswer tape(QueryFilter filter, String minDurationMillis, String after, String limit) {
        var query = new TapeQuery(filter(filter), minDuration(minDurationMillis), Optional.ofNullable(after).map(TapePageView::parseCursor), limit(limit));
        return answer(List.of("tape", query), epoch -> json(TapePageView.from(epoch.session(), epoch.tape(query))));
    }

    public QueryAnswer durations(QueryFilter filter, String minDurationMillis, String buckets) {
        var selected = filter(filter);
        var threshold = minDuration(minDurationMillis);
        var count = number("buckets", required("buckets", buckets));
        return answer(List.of("durations", selected, threshold, count),
            epoch -> json(DurationsView.from(epoch.session(), epoch.durations(selected, threshold, count))));
    }

    // Chains when true; the order is newest when absent.
    public QueryAnswer traces(QueryFilter filter, String chains, String order, String after, String limit) {
        var query = new TraceQuery(filter(filter), Boolean.parseBoolean(chains), order == null ? TraceOrder.NEWEST : QueryNames.order(order),
            Optional.ofNullable(after).map(TracePageView::parseCursor), limit(limit));
        return answer(List.of("traces", query), epoch -> json(TracePageView.from(epoch.session(), epoch.traces(query))));
    }

    // The ETag is of the trace and its members, not of the sequence the answer names, which moves with what else the hub retains.
    public QueryAnswer trace(String sequence, String ifNoneMatch) {
        var id = invocationId(sequence);
        return answer(List.of("trace", id), epoch -> epoch.trace(id)
            .map(detail -> TraceDetailView.from(epoch.session(), detail))
            .map(view -> versioned(view, List.of(view.trace(), view.members())))
            .orElseGet(QueryAnswer::notFound)).unlessHeld(ifNoneMatch);
    }

    public QueryAnswer failures(List<String> causes) {
        var asked = causes.stream().map(QueryNames::failureCause).toList();
        return answer(List.of("failures", asked), epoch -> json(FailureCausesView.from(epoch.session(), epoch.failures(asked))));
    }

    public QueryAnswer timeline(QueryFilter filter, String minDurationMillis, String spanMillis, String limit) {
        var query = new TimelineQuery(filter(filter), minDuration(minDurationMillis), millis("spanMillis", required("spanMillis", spanMillis)),
            number("limit", required("limit", limit)));
        return answer(List.of("timeline", query), epoch -> json(TimelineWindowView.from(epoch.session(), epoch.timeline(query))));
    }

    // The ETag is of the invocation, its lineage, and its children, not of the sequence the answer names, which moves with what else the hub retains.
    public QueryAnswer invocation(String sequence, String ifNoneMatch) {
        var id = invocationId(sequence);
        return answer(List.of("invocation", id), epoch -> epoch.message(id)
            .map(detail -> MessageDetailView.from(epoch.session(), detail))
            .map(view -> versioned(view, List.of(view.invocation(), view.ancestors(), view.children(), view.childCount(), Optional.ofNullable(view.propagatedFrom()))))
            .orElseGet(QueryAnswer::notFound)).unlessHeld(ifNoneMatch);
    }

    public QueryAnswer invocations(String scope, String messageIdPrefix, String limit) {
        var application = Optional.ofNullable(scope).map(ApplicationName::new);
        var prefix = required("messageIdPrefix", messageIdPrefix);
        var count = number("limit", required("limit", limit));
        return answer(List.of("invocations", application, prefix, count),
            epoch -> json(FoundView.from(epoch.session(), epoch.withMessageIdPrefix(application, prefix, count))));
    }

    public QueryAnswer type(String type) {
        var selected = QueryNames.type(required("type", type));
        return answer(List.of("types", selected), epoch -> json(RetainedTypeView.from(epoch.session(), epoch.type(selected), epoch.history(selected))));
    }

    // Answers from the latest epoch; queries never wait for the hub's writer, nor it for them.
    QueryAnswer answer(Object query, Function<Epoch, QueryAnswer> answer) {
        var epoch = hub.epoch();
        return answers.answer(epoch, query, () -> answer.apply(epoch));
    }

    private QueryAnswer json(Object view) {
        return QueryAnswer.json(mapper.writeValueAsString(view));
    }

    // An answer the page may keep and ask again for with its ETag, which a hash of the given version of it names.
    private QueryAnswer versioned(Object view, Object version) {
        return QueryAnswer.versioned(mapper.writeValueAsString(view), hash(mapper.writeValueAsString(version)));
    }

    private static String hash(String version) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(version.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("The JVM offers no SHA-256 to name the version of an answer", failure);
        }
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
