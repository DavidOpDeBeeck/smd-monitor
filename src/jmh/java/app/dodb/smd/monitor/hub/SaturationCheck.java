package app.dodb.smd.monitor.hub;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static java.util.Objects.requireNonNull;

// The throughput docs/monitor.md promises, checked: each expectation fills its scenario's whole history at the expected rate, as fast as the hub
// takes it, then offers that rate for one step, open loop, in a JVM of its own. The step must keep up: nothing refused or shed, the writer below
// 90% busy, and the queues drained. The numbers hold on the reference machine of docs/monitor.md; on other hardware the check names the ones the
// machine misses.
public final class SaturationCheck {

    static final List<Expectation> EXPECTATIONS = List.of(
        new Expectation("profile-standalone", 80_000),
        new Expectation("applications-5", 150_000),
        new Expectation("applications-20", 100_000),
        new Expectation("reference", 60_000),
        new Expectation("applications-100", 40_000),
        new Expectation("applications-200", 20_000),
        new Expectation("applications-500", 5_000));

    // Arguments: the output directory (default build/saturation-check), and optionally the scenarios to check (see SaturationScenario.selected).
    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        var output = Path.of(args.length > 0 ? args[0] : "build/saturation-check");
        var selected = args.length > 1 && !args[1].isBlank()
            ? SaturationScenario.selected(args[1]).stream().map(SaturationScenario::id).toList()
            : SaturationScenario.ALL.stream().map(SaturationScenario::id).toList();
        Files.createDirectories(output);
        var missed = new ArrayList<String>();
        var results = new ArrayList<String>();
        var expectations = EXPECTATIONS.stream().filter(expectation -> selected.contains(expectation.scenario())).toList();
        var scenarios = expectations.stream().map(expectation -> SaturationScenario.selected(expectation.scenario()).getFirst()).toList();
        var plan = new SaturationPlan(scenarios, output);
        for (var index = 0; index < expectations.size(); index++) {
            var expectation = expectations.get(index);
            var scenario = plan.scenarios().get(index);
            var progress = plan.progressOf(index);
            SaturationRun.runInOwnJvm(scenario, output, "", String.valueOf(expectation.spansPerSecond()), progress);
            plan.finished(progress);
            var verdict = Files.readAllLines(output.resolve(scenario.id() + ".verdicts")).getLast().split(",")[1];
            var line = "%s at %,d spans/s: %s".formatted(scenario.id(), expectation.spansPerSecond(), verdict);
            results.add(line);
            if (!verdict.equals(SaturationStep.Verdict.KEEPS_UP.name())) {
                missed.add(line);
            }
        }
        System.out.printf("%nSaturation check:%n%s%n", String.join(System.lineSeparator(), results));
        if (!missed.isEmpty()) {
            System.err.printf("%nThe hub no longer keeps up with the throughput docs/monitor.md promises:%n%s%n", String.join(System.lineSeparator(), missed));
            System.exit(1);
        }
    }

    record Expectation(String scenario, int spansPerSecond) {

        Expectation {
            requireNonNull(scenario);
            if (spansPerSecond < 1) {
                throw new IllegalArgumentException("Expectation of scenario " + scenario + " requires a positive rate; got " + spansPerSecond);
            }
        }
    }
}
