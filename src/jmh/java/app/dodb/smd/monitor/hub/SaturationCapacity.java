package app.dodb.smd.monitor.hub;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;

// How many open UIs the hub serves at which span rate: the frontier below which it keeps up and every viewer's UI stays usable
// (SaturationUsability). Without viewers it climbs the reference scenario's ladder of rates until the hub no longer keeps up; then it doubles the
// viewers, 1, 2, 4, and so on, and for each climbs from two rates below the last limit until a step is not usable. A climb whose first rate is
// not usable climbs again from the ladder's first; once even that is not usable, the viewers before were the most the hub serves, so a change that
// lets it serve more shows as more viewers, and no run tests viewers beyond the limit. Each climb is a saturation run in a JVM of its own that
// fills a minute of history, and writes into <output>/v-<n>; the frontier goes to <output>/capacity.json, which docs/saturation/dashboard.py
// --add-capacity keeps for the saturation page's Capacity view.
public final class SaturationCapacity {

    private static final int MAX_VIEWERS = 4_096;
    private static final int RATES_BELOW = 2;
    private static final String FILL = "PT1M";
    private static final String SCENARIO = "reference";

    // Arguments: the output directory (default build/saturation-capacity), and optionally how long a history to fill as an ISO-8601 duration.
    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        var output = Path.of(args.length > 0 ? args[0] : "build/saturation-capacity");
        var fill = args.length > 1 && !args[1].isBlank() ? args[1] : FILL;
        SaturationRun.nameRun();
        clearEarlierClimbs(output);
        var scenario = SaturationScenario.selected(SCENARIO).getFirst();
        var ladder = scenario.rates();
        var climber = new Climber(scenario, output, fill, Instant.now());
        var limits = new ArrayList<Limit>();
        var ingest = climber.climb(0, ladder);
        if (ingest.isEmpty()) {
            throw new IllegalStateException("Saturation capacity cannot start: without viewers the hub kept up with no rate of " + ladder);
        }
        limits.add(new Limit(0, ingest.getAsInt()));
        var previous = ingest.getAsInt();
        for (var viewers = 1; viewers <= MAX_VIEWERS; viewers *= 2) {
            var from = Math.max(0, ladder.indexOf(previous) - RATES_BELOW);
            var limit = climber.climb(viewers, ladder.subList(from, ladder.size()));
            if (limit.isEmpty() && from > 0) {
                limit = climber.climb(viewers, ladder.subList(0, from));
            }
            if (limit.isEmpty()) {
                System.out.printf("%nWith %,d viewers no rate from %,d spans/s keeps their UIs usable: the hub serves at most %,d%n", viewers, ladder.getFirst(),
                    viewers / 2);
                break;
            }
            limits.add(new Limit(viewers, limit.getAsInt()));
            previous = limit.getAsInt();
        }
        write(output, limits);
    }

    // A frontier starts afresh: an earlier run's climbs would be taken for this one's.
    private static void clearEarlierClimbs(Path output) throws IOException {
        Files.createDirectories(output);
        try (var entries = Files.list(output)) {
            for (var entry : entries.filter(path -> path.getFileName().toString().startsWith("v-") || path.getFileName().toString().equals("capacity.json")).toList()) {
                try (var files = Files.walk(entry)) {
                    for (var file : files.sorted(Comparator.reverseOrder()).toList()) {
                        Files.delete(file);
                    }
                }
            }
        }
    }

    private static void write(Path output, List<Limit> limits) throws Exception {
        var capacity = new LinkedHashMap<String, Object>();
        capacity.put("run", System.getProperty("saturation.run"));
        capacity.put("scenario", SCENARIO);
        capacity.put("recordedAt", Instant.now().toString());
        capacity.put("usable", Map.of("framesRead", SaturationUsability.FRAMES_READ, "frameLagMillis", SaturationUsability.FRAME_LAG_MILLIS,
            "queriesAnswered", SaturationUsability.QUERIES_ANSWERED, "answerMillis", SaturationUsability.ANSWER_MILLIS, "freshWorst",
            SaturationUsability.FRESH_WORST));
        capacity.put("limits", limits);
        Files.writeString(output.resolve("capacity.json"), new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(capacity));
        System.out.printf("%nCapacity of %s in %s: %s; add it to the saturation page with ./gradlew saturationDashboard -PsaturationCapacity%n", SCENARIO, output,
            limits.stream().map(limit -> "%,d viewers at %,d spans/s".formatted(limit.viewers(), limit.rate())).collect(Collectors.joining(", ")));
    }

    // The highest rate at which the hub kept up and, with viewers, their UIs stayed usable.
    record Limit(int viewers, int rate) {
    }

    // Runs the climbs one after the other, each in a JVM of its own.
    private static final class Climber {

        private static final Duration CLIMB_ESTIMATE = Duration.ofMinutes(3);

        private final SaturationScenario scenario;
        private final Path output;
        private final String fill;
        private final Instant started;
        private final ObjectMapper mapper = new ObjectMapper();
        private int climbs;

        private Climber(SaturationScenario scenario, Path output, String fill, Instant started) {
            this.scenario = requireNonNull(scenario);
            this.output = requireNonNull(output);
            this.fill = requireNonNull(fill);
            this.started = requireNonNull(started);
        }

        // The highest rate before the climb's first step that did not pass, or none when its first did not; it writes into <output>/v-<n>, or
        // v-<n>-from-<rate> when it climbs again from a lower rate.
        private OptionalInt climb(int viewers, List<Integer> rates) throws Exception {
            climbs++;
            var name = "v-" + viewers;
            var directory = output.resolve(Files.exists(output.resolve(name)) ? name + "-from-" + rates.getFirst() : name);
            Files.createDirectories(directory);
            var progress = new SaturationProgress(climbs, climbs, scenario.id(), started, Instant.now(), CLIMB_ESTIMATE, Duration.ZERO);
            SaturationRun.runInOwnJvm(scenario, directory, fill, rates.stream().map(String::valueOf).collect(Collectors.joining(",")), progress,
                List.of("-Dsaturation.viewers=" + viewers));
            var result = mapper.readTree(directory.resolve(scenario.id() + ".json").toFile());
            var passed = viewers == 0 ? passedRates(result.get("steps"), step -> step.get("verdict").asString().equals("KEEPS_UP"))
                : passedRates(result.get("usability"), step -> step.get("usable").asBoolean());
            return passed.isEmpty() ? OptionalInt.empty() : OptionalInt.of(passed.getLast());
        }

        // The rates of the steps in order up to the first that did not pass.
        private static List<Integer> passedRates(JsonNode steps, Predicate<JsonNode> passed) {
            var rates = new ArrayList<Integer>();
            for (var step : steps) {
                if (!passed.test(step)) {
                    break;
                }
                rates.add(step.get("rate").asInt());
            }
            return rates;
        }
    }
}
