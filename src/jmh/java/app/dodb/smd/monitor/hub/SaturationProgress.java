package app.dodb.smd.monitor.hub;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static java.util.Objects.requireNonNull;

// Where a saturation run stands: the scenario's position among the run's, how long the scenario and the run took so far, and about how long the
// run still takes. The parent JVM passes it to the JVM of each scenario, which starts every line it reports with it and keeps the latest line in
// <output>/progress.txt, so a run followed from elsewhere shows where it is with one read.
record SaturationProgress(int position, int total, String scenario, Instant runStarted, Instant scenarioStarted, Duration scenarioEstimate,
                          Duration laterEstimate) {

    private static final String PROPERTY = "saturation.progress";

    SaturationProgress {
        requireNonNull(scenario);
        requireNonNull(runStarted);
        requireNonNull(scenarioStarted);
        requireNonNull(scenarioEstimate);
        requireNonNull(laterEstimate);
    }

    // The progress the parent JVM passed, else that of a run of this scenario alone; either way the scenario started when this JVM did.
    static SaturationProgress of(SaturationScenario scenario, Path output) {
        var started = Instant.ofEpochMilli(ManagementFactory.getRuntimeMXBean().getStartTime());
        var passed = System.getProperty(PROPERTY);
        if (passed == null) {
            return new SaturationProgress(1, 1, scenario.id(), started, started, SaturationPlan.estimateOf(scenario, output), Duration.ZERO);
        }
        var parts = passed.split(",");
        if (parts.length != 5) {
            throw new IllegalArgumentException("SaturationProgress expects -D" + PROPERTY + "=<position>,<total>,<run started ms>,<scenario estimate s>,"
                + "<later estimate s>; got '" + passed + "'");
        }
        return new SaturationProgress(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), scenario.id(), Instant.ofEpochMilli(Long.parseLong(parts[2])),
            started, Duration.ofSeconds(Long.parseLong(parts[3])), Duration.ofSeconds(Long.parseLong(parts[4])));
    }

    String jvmArgument() {
        return "-D%s=%d,%d,%d,%d,%d".formatted(PROPERTY, position, total, runStarted.toEpochMilli(), scenarioEstimate.toSeconds(), laterEstimate.toSeconds());
    }

    Duration scenarioTook() {
        return Duration.between(scenarioStarted, Instant.now());
    }

    // Prints the line after where the run stands and keeps it as the latest.
    void report(Path output, String line) {
        var now = Instant.now();
        var scenarioLeft = scenarioEstimate.minus(Duration.between(scenarioStarted, now));
        var left = laterEstimate.plus(scenarioLeft.isNegative() ? Duration.ZERO : scenarioLeft);
        var reported = "[%d/%d %s %s, run %s, about %s left] %s".formatted(position, total, scenario, clock(Duration.between(scenarioStarted, now)),
            clock(Duration.between(runStarted, now)), clock(left), line);
        System.out.println(reported);
        try {
            Files.writeString(output.resolve("progress.txt"), reported + System.lineSeparator());
        } catch (IOException failure) {
            System.err.println("Saturation run could not write " + output.resolve("progress.txt") + ": " + failure.getMessage());
        }
    }

    static String clock(Duration duration) {
        var seconds = Math.max(0, duration.toSeconds());
        return seconds >= 3600
            ? "%d:%02d:%02d".formatted(seconds / 3600, seconds / 60 % 60, seconds % 60)
            : "%d:%02d".formatted(seconds / 60, seconds % 60);
    }
}
