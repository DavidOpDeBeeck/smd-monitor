package app.dodb.smd.monitor.hub.spring;

import app.dodb.smd.monitor.hub.ApplicationType;
import app.dodb.smd.monitor.hub.Epoch;
import app.dodb.smd.monitor.hub.FailureCause;
import app.dodb.smd.monitor.hub.InvocationFilter;
import app.dodb.smd.monitor.hub.InvocationId;
import app.dodb.smd.monitor.hub.MonitorHub;
import app.dodb.smd.monitor.hub.TapeQuery;
import app.dodb.smd.monitor.hub.TimelineQuery;
import app.dodb.smd.monitor.hub.TraceOrder;
import app.dodb.smd.monitor.hub.TraceQuery;
import app.dodb.smd.monitor.hub.TypeSelection;
import app.dodb.smd.monitor.hub.span.ApplicationName;
import app.dodb.smd.monitor.hub.span.MessageKind;
import app.dodb.smd.monitor.hub.span.MessageType;
import app.dodb.smd.monitor.hub.span.Outcome;
import app.dodb.smd.monitor.hub.span.Stage;
import app.dodb.smd.monitor.json.DurationsView;
import app.dodb.smd.monitor.json.FailureCausesView;
import app.dodb.smd.monitor.json.FoundView;
import app.dodb.smd.monitor.json.MessageDetailView;
import app.dodb.smd.monitor.json.Protocol;
import app.dodb.smd.monitor.json.RetainedTypeView;
import app.dodb.smd.monitor.json.TapePageView;
import app.dodb.smd.monitor.json.TimelineWindowView;
import app.dodb.smd.monitor.json.TraceDetailView;
import app.dodb.smd.monitor.json.TracePageView;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.function.Function;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;
import static org.springframework.http.CacheControl.noCache;
import static org.springframework.http.CacheControl.noStore;
import static org.springframework.http.ResponseEntity.badRequest;
import static org.springframework.http.ResponseEntity.ok;
import static org.springframework.http.ResponseEntity.status;

// The retained invocations a page asks for, each answered from the latest epoch. A scope names one application; a type names its application,
// kind, and class as application::KIND:class; durations are in milliseconds. Identical queries of one epoch are answered once (W5); beyond the
// queries running at once, the hub answers 429. A trace and an invocation answer with an ETag of their content, so a page asking again for one
// that did not change receives 304. Every answer names the protocol and the build (W6).
@RestController
@RequestMapping("/api")
class MonitorHubQueries {

    static final int MAX_RUNNING = 4;
    static final String PROTOCOL_HEADER = "X-Smd-Monitor-Protocol";
    static final String BUILD_HEADER = "X-Smd-Monitor-Build";
    private static final String DEFAULT_LIMIT = "100";
    // The hub's own mapper, so the application's Jackson settings never change the wire format the page reads.
    private final ObjectMapper mapper = new ObjectMapper();
    private final MonitorHub hub;
    private final Semaphore running = new Semaphore(MAX_RUNNING);
    private final QueryCache cache = new QueryCache();

    MonitorHubQueries(MonitorHub hub) {
        this.hub = requireNonNull(hub);
    }

    @GetMapping("/tape")
    ResponseEntity<String> tape(@RequestParam(name = "scope") Optional<String> scope,
                                @RequestParam(name = "application", required = false) List<String> applications,
                                @RequestParam(name = "kind", required = false) List<String> kinds,
                                @RequestParam(name = "type", required = false) List<String> types,
                                @RequestParam(name = "status", required = false) List<String> statuses,
                                @RequestParam(name = "search", defaultValue = "") String search,
                                @RequestParam(name = "minDurationMillis", defaultValue = "0") double minDurationMillis,
                                @RequestParam(name = "after") Optional<String> after,
                                @RequestParam(name = "limit", defaultValue = DEFAULT_LIMIT) int limit) {
        var filter = filter(scope, applications, kinds, types, statuses, search);
        var query = new TapeQuery(filter, millis(minDurationMillis), after.map(TapePageView::parseCursor), limit);
        return answer(List.of("tape", query), epoch -> json(TapePageView.from(epoch.session(), epoch.tape(query))));
    }

    @GetMapping("/tape/durations")
    ResponseEntity<String> durations(@RequestParam(name = "scope") Optional<String> scope,
                                     @RequestParam(name = "application", required = false) List<String> applications,
                                     @RequestParam(name = "kind", required = false) List<String> kinds,
                                     @RequestParam(name = "type", required = false) List<String> types,
                                     @RequestParam(name = "status", required = false) List<String> statuses,
                                     @RequestParam(name = "search", defaultValue = "") String search,
                                     @RequestParam(name = "minDurationMillis", defaultValue = "0") double minDurationMillis,
                                     @RequestParam(name = "buckets") int buckets) {
        var filter = filter(scope, applications, kinds, types, statuses, search);
        var threshold = millis(minDurationMillis);
        return answer(List.of("durations", filter, threshold, buckets), epoch -> json(DurationsView.from(epoch.session(), epoch.durations(filter, threshold, buckets))));
    }

    @GetMapping("/traces")
    ResponseEntity<String> traces(@RequestParam(name = "scope") Optional<String> scope,
                                  @RequestParam(name = "kind", required = false) List<String> kinds,
                                  @RequestParam(name = "type", required = false) List<String> types,
                                  @RequestParam(name = "application", required = false) List<String> applications,
                                  @RequestParam(name = "status", required = false) List<String> statuses,
                                  @RequestParam(name = "chains", defaultValue = "false") boolean chains,
                                  @RequestParam(name = "search", defaultValue = "") String search,
                                  @RequestParam(name = "order", defaultValue = "newest") String order,
                                  @RequestParam(name = "after") Optional<String> after,
                                  @RequestParam(name = "limit", defaultValue = DEFAULT_LIMIT) int limit) {
        var query = new TraceQuery(scope.map(ApplicationName::new), types(kinds, types), applications(applications), statuses(statuses), chains, search,
            order(order), after.map(TracePageView::parseCursor), limit);
        return answer(List.of("traces", query), epoch -> json(TracePageView.from(epoch.session(), epoch.traces(query))));
    }

    // The ETag is of the trace and its members, not of what else the hub retains.
    @GetMapping("/traces/{sequence}")
    ResponseEntity<String> trace(@PathVariable("sequence") long sequence, @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        return unlessUnchanged(ifNoneMatch, answer(List.of("trace", sequence), epoch -> epoch.trace(new InvocationId(sequence))
            .map(detail -> TraceDetailView.from(epoch.session(), detail))
            .map(view -> versioned(view, List.of(view.trace(), view.members())))
            .orElseGet(this::notFound)));
    }

    @GetMapping("/failures")
    ResponseEntity<String> failures(@RequestParam(name = "cause", required = false) List<String> causes) {
        var asked = each(causes).stream().map(MonitorHubQueries::failureCause).toList();
        return answer(List.of("failures", asked), epoch -> json(FailureCausesView.from(epoch.session(), epoch.failures(asked))));
    }

    @GetMapping("/timeline")
    ResponseEntity<String> timeline(@RequestParam(name = "scope") Optional<String> scope,
                                    @RequestParam(name = "application", required = false) List<String> applications,
                                    @RequestParam(name = "kind", required = false) List<String> kinds,
                                    @RequestParam(name = "type", required = false) List<String> types,
                                    @RequestParam(name = "status", required = false) List<String> statuses,
                                    @RequestParam(name = "search", defaultValue = "") String search,
                                    @RequestParam(name = "minDurationMillis", defaultValue = "0") double minDurationMillis,
                                    @RequestParam(name = "spanMillis") double spanMillis,
                                    @RequestParam(name = "limit") int limit) {
        var query = new TimelineQuery(filter(scope, applications, kinds, types, statuses, search), millis(minDurationMillis), millis(spanMillis), limit);
        return answer(List.of("timeline", query), epoch -> json(TimelineWindowView.from(epoch.session(), epoch.timeline(query))));
    }

    // The ETag is of the invocation, its lineage, and its children, not of what else the hub retains.
    @GetMapping("/invocations/{sequence}")
    ResponseEntity<String> invocation(@PathVariable("sequence") long sequence,
                                      @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        return unlessUnchanged(ifNoneMatch, answer(List.of("invocation", sequence), epoch -> epoch.message(new InvocationId(sequence))
            .map(detail -> MessageDetailView.from(epoch.session(), detail))
            .map(view -> versioned(view, List.of(view.invocation(), view.ancestors(), view.children(), view.childCount(), Optional.ofNullable(view.propagatedFrom()))))
            .orElseGet(this::notFound)));
    }

    @GetMapping("/invocations")
    ResponseEntity<String> invocations(@RequestParam(name = "scope") Optional<String> scope, @RequestParam(name = "messageIdPrefix") String messageIdPrefix, @RequestParam(name = "limit") int limit) {
        var application = scope.map(ApplicationName::new);
        return answer(List.of("invocations", application, messageIdPrefix, limit),
            epoch -> json(FoundView.from(epoch.session(), epoch.withMessageIdPrefix(application, messageIdPrefix, limit))));
    }

    @GetMapping("/types")
    ResponseEntity<String> type(@RequestParam(name = "type") String type) {
        var selected = applicationType(type);
        return answer(List.of("types", selected), epoch -> json(RetainedTypeView.from(epoch.session(), epoch.type(selected), epoch.history(selected))));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<String> rejected(IllegalArgumentException failure) {
        return badRequest().headers(protocol()).contentType(MediaType.TEXT_PLAIN).body(failure.getMessage());
    }

    // Answers from the latest epoch, once per query and epoch, unless as many queries run already; they never wait for the hub's writer, nor
    // it for them.
    ResponseEntity<String> answer(Object query, Function<Epoch, ResponseEntity<String>> answer) {
        var epoch = hub.epoch();
        var answered = cache.answer(epoch, query, () -> {
            if (!running.tryAcquire()) {
                return null;
            }
            try {
                return answer.apply(epoch);
            } finally {
                running.release();
            }
        });
        if (answered == null) {
            return status(HttpStatus.TOO_MANY_REQUESTS).headers(protocol()).cacheControl(noStore()).contentType(MediaType.TEXT_PLAIN)
                .body("The SMD monitor hub answers at most " + MAX_RUNNING + " queries at once; ask again shortly");
        }
        return answered;
    }

    private ResponseEntity<String> json(Object view) {
        return ok().headers(protocol()).cacheControl(noStore()).contentType(MediaType.APPLICATION_JSON).body(mapper.writeValueAsString(view));
    }

    // An answer the page may keep and ask again for with its ETag, which a hash of the given version of it names.
    private ResponseEntity<String> versioned(Object view, Object version) {
        return ok().headers(protocol()).cacheControl(noCache()).eTag(hash(mapper.writeValueAsString(version))).contentType(MediaType.APPLICATION_JSON)
            .body(mapper.writeValueAsString(view));
    }

    private ResponseEntity<String> notFound() {
        return status(HttpStatus.NOT_FOUND).headers(protocol()).cacheControl(noStore()).build();
    }

    // 304 when the page holds the answer with the same ETag already.
    private static ResponseEntity<String> unlessUnchanged(String ifNoneMatch, ResponseEntity<String> answer) {
        var eTag = answer.getHeaders().getETag();
        if (ifNoneMatch == null || eTag == null || !ifNoneMatch.equals(eTag)) {
            return answer;
        }
        return status(HttpStatus.NOT_MODIFIED).headers(answer.getHeaders()).build();
    }

    private static HttpHeaders protocol() {
        var headers = new HttpHeaders();
        headers.set(PROTOCOL_HEADER, String.valueOf(Protocol.VERSION));
        headers.set(BUILD_HEADER, Protocol.build());
        return headers;
    }

    private static String hash(String version) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(version.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("The JVM offers no SHA-256 to name the version of an answer", failure);
        }
    }

    private static InvocationFilter filter(Optional<String> scope, List<String> applications, List<String> kinds, List<String> types, List<String> statuses,
                                           String search) {
        return new InvocationFilter(scope.map(ApplicationName::new), applications(applications), types(kinds, types), statuses(statuses), search);
    }

    private static Set<ApplicationName> applications(List<String> applications) {
        return each(applications).stream().map(ApplicationName::new).collect(Collectors.toSet());
    }

    private static TypeSelection types(List<String> kinds, List<String> types) {
        return new TypeSelection(each(kinds).stream().map(kind -> constant(MessageKind.class, kind, "kind")).collect(Collectors.toSet()),
            each(types).stream().map(MonitorHubQueries::applicationType).collect(Collectors.toSet()));
    }

    private static Set<Outcome.Status> statuses(List<String> statuses) {
        return each(statuses).stream().map(status -> constant(Outcome.Status.class, status, "status")).collect(Collectors.toSet());
    }

    private static TraceOrder order(String order) {
        return constant(TraceOrder.class, order.toUpperCase(Locale.ROOT), "trace order");
    }

    // application::KIND:class, as the page names a type, with the application percent-encoded so that it holds no ':', '|', or '@'.
    private static ApplicationType applicationType(String type) {
        var application = type.indexOf("::");
        var kind = type.indexOf(':', application + 2);
        if (application < 1 || kind < 0) {
            throw new IllegalArgumentException("A type is named as application::KIND:class, such as tickets::COMMAND:app.OpenTicket; got '" + type + "'");
        }
        return new ApplicationType(new ApplicationName(decoded(type.substring(0, application), type)),
            new MessageType(constant(MessageKind.class, type.substring(application + 2, kind), "kind"), type.substring(kind + 1)));
    }

    // As encodeURIComponent encodes, which writes a '+' as %2B, so a '+' is itself rather than a space.
    private static String decoded(String application, String type) {
        try {
            return URLDecoder.decode(application.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("A type names its application percent-encoded, such as Order%20Service::COMMAND:app.PlaceOrder; got '" + type
                + "'", invalid);
        }
    }

    // application::KIND:class|failure type|dispatch, or application::EVENT:class|failure type|handling:processing group, as the page names a
    // failure cause.
    private static FailureCause.Step failureCause(String cause) {
        var type = cause.indexOf('|');
        var stage = cause.indexOf('|', type + 1);
        if (type < 0 || stage < 0) {
            throw new IllegalArgumentException("A failure cause is named as application::KIND:class|failure type|dispatch or |handling:group, "
                + "such as tickets::COMMAND:app.OpenTicket|java.lang.IllegalStateException|dispatch; got '" + cause + "'");
        }
        var applicationType = applicationType(cause.substring(0, type));
        var failureType = cause.substring(type + 1, stage);
        var named = cause.substring(stage + 1);
        Stage parsed;
        if (named.equals("dispatch")) {
            parsed = new Stage.Dispatch(applicationType.type());
        } else if (named.startsWith("handling:") && applicationType.type().kind() == MessageKind.EVENT) {
            parsed = new Stage.Handling(applicationType.type(), named.substring("handling:".length()));
        } else {
            throw new IllegalArgumentException("A failure cause names its stage as dispatch, or as handling:group for an event; got '" + named + "' in '"
                + cause + "'");
        }
        return new FailureCause.Step(applicationType.application(), parsed, failureType);
    }

    private static <E extends Enum<E>> E constant(Class<E> type, String name, String meaning) {
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("Unknown " + meaning + " '" + name + "'; expected one of " + List.of(type.getEnumConstants()), failure);
        }
    }

    private static Duration millis(double millis) {
        if (!Double.isFinite(millis) || millis < 0) {
            throw new IllegalArgumentException("A duration in milliseconds must be a finite number of at least 0; got " + millis);
        }
        return Duration.ofNanos(Math.round(millis * 1_000_000));
    }

    private static List<String> each(List<String> values) {
        return values == null ? List.of() : values;
    }
}
