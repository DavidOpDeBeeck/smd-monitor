package app.dodb.smd.monitor.hub.intake;

import app.dodb.smd.monitor.hub.HubConfig;
import app.dodb.smd.monitor.hub.intake.Verdict.Admitted;
import app.dodb.smd.monitor.hub.intake.Verdict.Refused;
import app.dodb.smd.monitor.hub.span.ApplicationName;
import app.dodb.smd.monitor.hub.span.InstanceName;
import app.dodb.smd.monitor.hub.span.SessionId;
import app.dodb.smd.monitor.hub.span.SpanRef;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static java.util.Comparator.comparing;
import static java.util.Objects.requireNonNull;

// Admits each batch on the thread that received it, before it is queued, so an exporter learns at once what the hub refused. A batch whose
// application or instance is beyond the hub's limits is refused whole; a span that starts too far ahead of the hub's clock is refused on its
// own. Admission also owns each instance's session and liveness: an instance that sent nothing for retire-after retires, and an application
// left without instances retires with it, freeing its place.
public final class Admission {

    // How far ahead of the hub's clock a span may start. Instances' clocks drift, but a span further ahead would count in a second the windows
    // reach only later.
    public static final Duration MAX_CLOCK_SKEW = Duration.ofSeconds(30);
    // How long an instance stays flagged as clock-ahead after admission last refused a span of it for starting too far ahead.
    public static final Duration CLOCK_AHEAD_FLAGGED = Duration.ofMinutes(1);

    private final HubConfig config;
    // All mutable state below is guarded by this admission.
    private final Map<ApplicationName, Map<InstanceName, Reporting>> applications = new TreeMap<>(comparing(ApplicationName::value));
    private long beyondApplications;
    private final Map<ApplicationName, Long> beyondInstances = new HashMap<>();
    private final Map<ApplicationName, Long> aheadOfClock = new HashMap<>();

    public Admission(HubConfig config) {
        this.config = requireNonNull(config);
    }

    public synchronized Verdict admit(SpanBatch batch, Instant now) {
        var name = batch.application();
        var instances = applications.get(name);
        if (instances == null && applications.size() >= config.maxApplications()) {
            beyondApplications += batch.size();
            return refused(batch, "The hub already monitors " + config.maxApplications() + " applications; application '" + name
                + "' cannot report until one retires");
        }
        if (instances == null) {
            instances = new TreeMap<>(comparing(InstanceName::value));
            applications.put(name, instances);
        }
        var instance = instances.get(batch.instance());
        if (instance == null && instances.size() >= config.maxInstances() && !retireLongestSilent(instances, now)) {
            beyondInstances.merge(name, (long) batch.size(), Long::sum);
            return refused(batch, "Application '" + name + "' already has " + config.maxInstances() + " reporting instances; instance '" + batch.instance()
                + "' cannot report until one turns silent");
        }
        if (instance == null) {
            instance = new Reporting();
            instances.put(batch.instance(), instance);
        }
        instance.session = batch.session();
        instance.lastReportedAt = now;
        return admitStarted(batch, instance, now);
    }

    // Retires every instance that sent nothing for retire-after, and then every application left without instances, which it returns.
    public synchronized List<ApplicationName> retire(Instant now) {
        var retired = new ArrayList<ApplicationName>();
        for (var iterator = applications.entrySet().iterator(); iterator.hasNext(); ) {
            var application = iterator.next();
            application.getValue().values().removeIf(instance -> !instance.lastReportedAt.plus(config.retireAfter()).isAfter(now));
            if (application.getValue().isEmpty()) {
                iterator.remove();
                beyondInstances.remove(application.getKey());
                aheadOfClock.remove(application.getKey());
                retired.add(application.getKey());
            }
        }
        return retired;
    }

    // The instances of each application that did not retire, by name.
    public synchronized Map<ApplicationName, List<ReportingInstance>> instances(Instant now) {
        var reporting = new TreeMap<ApplicationName, List<ReportingInstance>>(comparing(ApplicationName::value));
        applications.forEach((application, instances) -> reporting.put(application, instances.entrySet().stream()
            .filter(instance -> instance.getValue().lastReportedAt.plus(config.retireAfter()).isAfter(now))
            .map(instance -> new ReportingInstance(instance.getKey(), instance.getValue().session, instance.getValue().lastReportedAt, silent(instance.getValue(), now),
                clockAhead(instance.getValue(), now)))
            .toList()));
        return reporting;
    }

    public synchronized RefusedSpans refused() {
        return new RefusedSpans(beyondApplications, Map.copyOf(beyondInstances), Map.copyOf(aheadOfClock));
    }

    private Verdict admitStarted(SpanBatch batch, Reporting instance, Instant now) {
        var latest = now.plus(MAX_CLOCK_SKEW);
        var spans = batch.spans().stream().filter(span -> !span.startedAt().isAfter(latest)).toList();
        var sends = batch.sends().stream().filter(send -> !send.startedAt().isAfter(latest)).toList();
        var refused = batch.size() - spans.size() - sends.size();
        if (refused == 0) {
            return new Admitted(batch, Optional.empty());
        }
        aheadOfClock.merge(batch.application(), (long) refused, Long::sum);
        instance.aheadOfClockAt = now;
        var first = batch.spans().stream().filter(span -> span.startedAt().isAfter(latest)).findFirst()
            .map(span -> aheadOfTheClock(span.span(), span.startedAt(), batch.instance(), now))
            .orElseGet(() -> batch.sends().stream().filter(send -> send.startedAt().isAfter(latest)).findFirst()
                .map(send -> aheadOfTheClock(send.span(), send.startedAt(), batch.instance(), now))
                .orElseThrow());
        return new Admitted(new SpanBatch(batch.application(), batch.instance(), batch.session(), spans, sends), Optional.of(new Refusal(refused, first)));
    }

    // A full application makes room for a new instance by retiring the one that has been silent longest.
    private boolean retireLongestSilent(Map<InstanceName, Reporting> instances, Instant now) {
        var longestSilent = instances.entrySet().stream()
            .filter(instance -> silent(instance.getValue(), now))
            .min(comparing(instance -> instance.getValue().lastReportedAt));
        longestSilent.ifPresent(instance -> instances.remove(instance.getKey()));
        return longestSilent.isPresent();
    }

    private boolean silent(Reporting instance, Instant now) {
        return !instance.lastReportedAt.plus(config.silentAfter()).isAfter(now);
    }

    private static boolean clockAhead(Reporting instance, Instant now) {
        return instance.aheadOfClockAt != null && instance.aheadOfClockAt.plus(CLOCK_AHEAD_FLAGGED).isAfter(now);
    }

    // An empty batch only reports its instance, so it has nothing to refuse.
    private static Verdict refused(SpanBatch batch, String reason) {
        return batch.size() == 0 ? new Admitted(batch, Optional.empty()) : new Refused(new Refusal(batch.size(), reason));
    }

    private static String aheadOfTheClock(SpanRef span, Instant startedAt, InstanceName instance, Instant now) {
        return "SMD span " + span.spanId() + " of instance '" + instance + "' started at " + startedAt + ", more than " + MAX_CLOCK_SKEW.toSeconds()
            + " seconds after the hub's clock at " + now + "; the instance's clock runs ahead of the hub's";
    }

    private static final class Reporting {

        private SessionId session;
        private Instant lastReportedAt;
        // When admission last refused a span of it for starting too far ahead of the hub's clock, or null.
        private Instant aheadOfClockAt;
    }
}
