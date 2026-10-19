package app.dodb.smd.monitor.hub;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static java.util.Objects.requireNonNull;

// The scenarios of one run, each run in a JVM of its own, in order, with an estimate of how long each takes: as long as it took the run before,
// from its result in the output directory. Once scenarios finished, the estimates of the rest scale by how long those took against theirs.
final class SaturationPlan {

    // A scenario without a result, or with one from before results kept their duration: the fill and steps of 30 seconds.
    private static final Duration UNKNOWN_SCENARIO = Duration.ofMinutes(5);
    private static final Duration FILL = Duration.ofMinutes(1);
    private static final Duration FORMER_STEP = Duration.ofSeconds(30);

    private final List<SaturationScenario> scenarios;
    private final Path output;
    private final List<Duration> estimates;
    private final Instant started = Instant.now();
    private Duration took = Duration.ZERO;
    private Duration estimatedTook = Duration.ZERO;

    SaturationPlan(List<SaturationScenario> scenarios, Path output) {
        this.scenarios = requireNonNull(scenarios);
        this.output = requireNonNull(output);
        this.estimates = scenarios.stream().map(scenario -> estimateOf(scenario, output)).toList();
    }

    List<SaturationScenario> scenarios() {
        return scenarios;
    }

    SaturationProgress progressOf(int index) {
        var later = estimates.subList(index + 1, estimates.size()).stream().reduce(Duration.ZERO, Duration::plus);
        return new SaturationProgress(index + 1, scenarios.size(), scenarios.get(index).id(), started, Instant.now(), scaled(estimates.get(index)),
            scaled(later));
    }

    void finished(SaturationProgress progress) {
        var index = progress.position() - 1;
        took = took.plus(progress.scenarioTook());
        estimatedTook = estimatedTook.plus(estimates.get(index));
        var later = estimates.subList(index + 1, estimates.size()).stream().reduce(Duration.ZERO, Duration::plus);
        new SaturationProgress(progress.position(), progress.total(), progress.scenario(), started, progress.scenarioStarted(), Duration.ZERO, scaled(later))
            .report(output, "done");
    }

    private Duration scaled(Duration estimate) {
        if (estimatedTook.isZero()) {
            return estimate;
        }
        return Duration.ofMillis(estimate.toMillis() * took.toMillis() / estimatedTook.toMillis());
    }

    static Duration estimateOf(SaturationScenario scenario, Path output) {
        var result = output.resolve(scenario.id() + ".json");
        if (!Files.exists(result)) {
            return UNKNOWN_SCENARIO;
        }
        try {
            var json = new ObjectMapper().readTree(Files.readString(result));
            if (json.path("durationS").isNumber()) {
                return Duration.ofSeconds(json.path("durationS").asLong());
            }
            return FILL.plus(FORMER_STEP.multipliedBy(json.path("steps").size()));
        } catch (IOException | JacksonException unreadable) {
            return UNKNOWN_SCENARIO;
        }
    }
}
