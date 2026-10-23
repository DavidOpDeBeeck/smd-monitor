package app.dodb.smd.monitor.hub;

import app.dodb.smd.monitor.hub.span.ApplicationName;
import app.dodb.smd.monitor.hub.span.Stage;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static java.util.Objects.requireNonNull;

// The open pages of a saturation run's viewers (SaturationViewers): each asks the hub's query API over HTTP for its view whenever its viewer
// reads the frame of a later epoch, as a visible page asks again whenever its view's sequence moves (frontend/src/store/hub-query.ts), and waits
// for the answer before it asks again, so a slow answer skips epochs. Like the page, it sends back the ETag of the answer it holds, and an answer
// that did not change comes back as 304: the page's view is as fresh as the hub's. The pages take the views in turn: the tape, the traces, the failures of the 20
// causes the scope counted most in its window when the run filled, and the timeline of the last minute. The first four ask of the whole system
// and each later one of one application, so pages share an answer, which the hub works out once per epoch, only when they share a view and a
// scope. A 429 answers too many at once, and a query the hub does not answer within the timeout, or whose connection fails, failed: both leave
// the page with its last answer. Any other answer than those or a success is a fault of the run, which stops it. With -Dsaturation.queryCap=N
// the hub runs at most N queries at once instead of its own limit.
final class SaturationPages implements AutoCloseable {

    private static final String QUERY_CAP_PROPERTY = "saturation.queryCap";
    private static final String SYSTEM = "system";
    private static final int SHOWN_CAUSES = 20;
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final URI api;
    private final Consumer<Query> asked;
    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(TIMEOUT).build();
    private final AtomicReference<String> fault = new AtomicReference<>();
    private final List<Latest> latest = new ArrayList<>();
    private final List<Thread> pages = new ArrayList<>();

    private SaturationPages(URI api, Consumer<Query> asked) {
        this.api = requireNonNull(api);
        this.asked = requireNonNull(asked);
    }

    // How many queries -Dsaturation.queryCap lets the hub run at once; the hub's own limit when absent.
    static OptionalInt queryCap() {
        var value = System.getProperty(QUERY_CAP_PROPERTY);
        if (value == null) {
            return OptionalInt.empty();
        }
        try {
            var cap = Integer.parseInt(value.strip());
            if (cap >= 1) {
                return OptionalInt.of(cap);
            }
        } catch (NumberFormatException ignored) {
            // Reported below.
        }
        throw new IllegalArgumentException("Saturation run expects -D" + QUERY_CAP_PROPERTY + " to be a whole number, 1 or more; got '" + value + "'");
    }

    // What each of the given number of pages asks, from the epoch the run filled.
    static List<Plan> plan(Epoch epoch, int count) {
        var applications = epoch.statistics().applications().stream().map(ApplicationStatistics::application)
            .sorted(Comparator.comparing(ApplicationName::value)).toList();
        var views = View.values().length;
        return IntStream.range(0, count).mapToObj(index -> {
            var view = View.values()[index % views];
            var scope = index < views || applications.isEmpty() ? Optional.<ApplicationName>empty()
                : Optional.of(applications.get((index / views - 1) % applications.size()));
            return new Plan(index, view, scope, view.path() + "?" + view.parameters(epoch, scope));
        }).toList();
    }

    static void writePlan(Path file, List<Plan> plans) throws IOException {
        Files.write(file, plans.stream().map(plan -> String.join("\t", String.valueOf(plan.page()), plan.view().key(), scopeKey(plan.scope()), plan.query()))
            .toList());
    }

    static List<Plan> readPlan(Path file) throws IOException {
        return Files.readAllLines(file).stream().map(line -> line.split("\t", -1))
            .map(fields -> new Plan(Integer.parseInt(fields[0]), View.of(fields[1]), scope(fields[2]), fields[3])).toList();
    }

    // Opens a page per plan, each asking once its viewer read a frame, and handing each query to the listener as it is answered or fails.
    static SaturationPages open(URI api, List<Plan> plans, Consumer<Query> asked) {
        var pages = new SaturationPages(api, asked);
        for (var plan : plans) {
            var latest = new Latest();
            pages.latest.add(latest);
            pages.pages.add(Thread.ofVirtual().name("saturation-page-" + plan.page()).start(() -> pages.browse(plan, latest)));
        }
        return pages;
    }

    // The page's viewer read the frame of the epoch.
    void read(int page, long epoch) {
        latest.get(page).read(epoch);
    }

    Optional<String> fault() {
        return Optional.ofNullable(fault.get());
    }

    // Every query a page asked, by the epoch it asked after.
    @Override
    public void close() throws InterruptedException {
        pages.forEach(Thread::interrupt);
        for (var page : pages) {
            page.join();
        }
        client.close();
    }

    private void browse(Plan plan, Latest latest) {
        var uri = api.resolve(plan.query());
        try {
            var held = Optional.<String>empty();
            for (var asked = -1L; !Thread.currentThread().isInterrupted(); ) {
                asked = latest.awaitAfter(asked);
                var request = HttpRequest.newBuilder(uri).timeout(TIMEOUT).GET();
                held.ifPresent(eTag -> request.header("If-None-Match", eTag));
                var started = System.nanoTime();
                var headers = new AtomicLong(started);
                try {
                    var response = client.send(request.build(), info -> {
                        headers.set(System.nanoTime());
                        return HttpResponse.BodySubscribers.ofByteArray();
                    });
                    var nanos = System.nanoTime() - started;
                    var answer = switch (response.statusCode()) {
                        case 200 -> Answer.ANSWERED;
                        case 304 -> Answer.UNCHANGED;
                        case 429 -> Answer.REFUSED;
                        default -> null;
                    };
                    if (answer == Answer.ANSWERED) {
                        held = response.headers().firstValue("ETag");
                    }
                    if (answer == null) {
                        fault.compareAndSet(null, "query " + uri + " answered " + response.statusCode() + ": " + new String(response.body(), StandardCharsets.UTF_8));
                        return;
                    }
                    this.asked.accept(new Query(asked, plan.page(), plan.view(), plan.scope(), answer, nanos, headers.get() - started, response.body().length));
                } catch (IOException failure) {
                    this.asked.accept(new Query(asked, plan.page(), plan.view(), plan.scope(), Answer.FAILED, System.nanoTime() - started, 0, 0));
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static String scopeKey(Optional<ApplicationName> scope) {
        return scope.map(ApplicationName::value).orElse(SYSTEM);
    }

    private static Optional<ApplicationName> scope(String key) {
        return key.equals(SYSTEM) ? Optional.empty() : Optional.of(new ApplicationName(key));
    }

    // The latest epoch the page's viewer read a frame of; its page waits for a later one.
    private static final class Latest {

        private long epoch = -1;

        private synchronized void read(long epoch) {
            if (epoch > this.epoch) {
                this.epoch = epoch;
                notifyAll();
            }
        }

        private synchronized long awaitAfter(long asked) throws InterruptedException {
            while (epoch <= asked) {
                wait();
            }
            return epoch;
        }
    }

    // What a page asks: its view of its scope, as a path and query string of the API.
    record Plan(int page, View view, Optional<ApplicationName> scope, String query) {

        Plan {
            requireNonNull(view);
            requireNonNull(scope);
            requireNonNull(query);
        }
    }

    // How the hub answered: with an answer, with 304 for the one the page holds, with 429, or not in time.
    enum Answer {
        ANSWERED("answered"),
        UNCHANGED("unchanged"),
        REFUSED("refused"),
        FAILED("failed");

        private final String key;

        Answer(String key) {
            this.key = key;
        }

        String key() {
            return key;
        }

        static Answer of(String key) {
            return Arrays.stream(values()).filter(answer -> answer.key.equals(key)).findFirst().orElseThrow();
        }
    }

    enum View {
        TAPE("tape", "/api/tape"),
        TRACES("traces", "/api/traces"),
        FAILURES("failures", "/api/failures"),
        TIMELINE("timeline", "/api/timeline");

        private final String key;
        private final String path;

        View(String key, String path) {
            this.key = key;
            this.path = path;
        }

        String key() {
            return key;
        }

        static View of(String key) {
            return Arrays.stream(values()).filter(view -> view.key.equals(key)).findFirst().orElseThrow();
        }

        private String path() {
            return path;
        }

        // As the page asks: the tape and the traces 100 at a time, the timeline of the last minute up to 2,000 invocations, and the failures of the
        // causes counted most in the scope's window, as format.ts names them.
        private String parameters(Epoch epoch, Optional<ApplicationName> scope) {
            var scoped = scope.map(application -> "scope=" + encoded(application.value()) + "&").orElse("");
            return switch (this) {
                case TAPE, TRACES -> scoped + "limit=100";
                case TIMELINE -> scoped + "spanMillis=60000&limit=2000";
                case FAILURES -> causes(epoch, scope);
            };
        }

        private static String causes(Epoch epoch, Optional<ApplicationName> scope) {
            record Counted(ApplicationName application, RaisedFailureStatistics failure) {
            }
            return epoch.statistics().applications().stream()
                .filter(application -> scope.isEmpty() || scope.get().equals(application.application()))
                .flatMap(application -> application.failures().stream().map(failure -> new Counted(application.application(), failure)))
                .sorted(Comparator.comparingLong((Counted counted) -> counted.failure().window()).reversed())
                .limit(SHOWN_CAUSES)
                .map(counted -> "cause=" + encoded(cause(counted.application(), counted.failure())))
                .collect(Collectors.joining("&"));
        }

        private static String cause(ApplicationName application, RaisedFailureStatistics failure) {
            var stage = failure.failure().stage();
            var type = stage.type();
            var named = switch (stage) {
                case Stage.Dispatch ignored -> "dispatch";
                case Stage.Handling(var ignored, var processingGroup) -> "handling:" + processingGroup;
            };
            return encoded(application.value()) + "::" + type.kind().name() + ":" + type.name() + "|" + failure.failure().failureType() + "|" + named;
        }

        private static String encoded(String value) {
            return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
        }
    }

    // One query a page asked: the epoch it asked after, the page, its view and scope, how the hub answered and after how long, and the bytes of
    // the answer.
    // The nanos run from asking to the answer's last byte read, of which the headers took the first.
    record Query(long epoch, int page, View view, Optional<ApplicationName> scope, Answer answer, long nanos, long headersNanos, long bytes) {

        Query {
            requireNonNull(view);
            requireNonNull(scope);
            requireNonNull(answer);
        }

        String line() {
            return String.join(",", String.valueOf(epoch), String.valueOf(page), view.key(), scopeKey(scope), answer.key(), String.valueOf(nanos),
                String.valueOf(headersNanos), String.valueOf(bytes));
        }

        static Query parse(String line) {
            var fields = line.split(",");
            return new Query(Long.parseLong(fields[0]), Integer.parseInt(fields[1]), View.of(fields[2]), SaturationPages.scope(fields[3]), Answer.of(fields[4]),
                Long.parseLong(fields[5]), Long.parseLong(fields[6]), Long.parseLong(fields[7]));
        }
    }
}
