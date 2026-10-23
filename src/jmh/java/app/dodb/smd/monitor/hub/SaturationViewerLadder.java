package app.dodb.smd.monitor.hub;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

// How many open UIs the hub serves while it ingests, and at what cost: a run without viewers, then one per number of viewers and query cap, each a
// saturation run of the reference scenario in a JVM of its own that fills a minute of history and offers one rate for a step while the viewers
// are open (SaturationViewers). At each number of viewers the query caps take turns, so drift of the machine meets every cap alike. Each run
// writes into <output>/baseline or <output>/cap-<c>/v-<n>, and docs/saturation/dashboard.py --add-viewers keeps the ladder for the saturation
// page's Open UIs view.
public final class SaturationViewerLadder {

    static final List<Integer> VIEWERS = List.of(1, 8, 16, 32, 48, 64, 128);
    static final List<Integer> QUERY_CAPS = List.of(4, 16, 64);
    private static final String RATE = "60000";
    private static final String FILL = "PT1M";
    private static final Duration POINT_ESTIMATE = Duration.ofSeconds(70);

    // Arguments: the output directory (default build/saturation-viewers), and optionally the comma-separated numbers of viewers, query caps, the
    // rate to offer, and how long a history to fill as an ISO-8601 duration.
    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        var output = Path.of(args.length > 0 ? args[0] : "build/saturation-viewers");
        var viewers = args.length > 1 && !args[1].isBlank() ? numbers(args[1]) : VIEWERS;
        var caps = args.length > 2 && !args[2].isBlank() ? numbers(args[2]) : QUERY_CAPS;
        var rate = args.length > 3 && !args[3].isBlank() ? args[3] : RATE;
        var fill = args.length > 4 && !args[4].isBlank() ? args[4] : FILL;
        SaturationRun.nameRun();
        var scenario = SaturationScenario.selected("reference").getFirst();
        var points = new ArrayList<Point>();
        points.add(new Point(output.resolve("baseline"), List.of("-Dsaturation.viewers=0")));
        for (var count : viewers) {
            for (var cap : caps) {
                points.add(new Point(output.resolve("cap-" + cap).resolve("v-" + count), List.of("-Dsaturation.viewers=" + count, "-Dsaturation.queryCap=" + cap)));
            }
        }
        var started = Instant.now();
        for (var index = 0; index < points.size(); index++) {
            var point = points.get(index);
            Files.createDirectories(point.output());
            var progress = new SaturationProgress(index + 1, points.size(), scenario.id(), started, Instant.now(), POINT_ESTIMATE,
                POINT_ESTIMATE.multipliedBy(points.size() - index - 1));
            SaturationRun.runInOwnJvm(scenario, point.output(), fill, rate, progress, point.properties());
        }
        System.out.printf("%nViewer ladder of %d runs in %s; add it to the saturation page with ./gradlew saturationDashboard -PsaturationViewers%n",
            points.size(), output);
    }

    private static List<Integer> numbers(String values) {
        return Arrays.stream(values.split(",")).map(String::strip).filter(value -> !value.isEmpty()).map(Integer::valueOf).toList();
    }

    // One run of the ladder: where it writes, and the properties that open its viewers.
    private record Point(Path output, List<String> properties) {
    }
}
