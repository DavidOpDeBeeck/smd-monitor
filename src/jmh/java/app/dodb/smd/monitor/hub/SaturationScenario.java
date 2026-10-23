package app.dodb.smd.monitor.hub;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static java.util.Objects.requireNonNull;

// A shape of applications, instances, and keys that a saturation run fills for its history at the first rate, then offers each rate for a step,
// in order, until the hub falls behind or refuses. Every scenario climbs the same ladder up to its maximum, so the limits of two runs compare
// on one scale and an improvement is never cut off by a scenario's own last rate.
//
// The scenarios compare: each sweep changes one dimension of the reference shape and keeps the others, so a difference between two of its
// scenarios has one cause. Only the applications sweep has several points, as the writer's cost per span grows with applications; the other
// sweeps keep the one point where their dimension costs most. The profile is the design target, which docs/monitor.md documents for users.
record SaturationScenario(String id, String name, Sweep sweep, LoadProfile profile, List<Integer> rates, Duration step) {

    private static final long MEGABYTE = 1024 * 1024;
    private static final Duration WINDOW = Duration.ofMinutes(1);
    private static final Duration STEP = Duration.ofSeconds(30);
    private static final List<Integer> LADDER = List.of(2_000, 5_000, 10_000, 20_000, 30_000, 40_000, 50_000, 60_000, 80_000, 100_000, 125_000, 150_000, 200_000,
        250_000, 300_000);
    private static final List<String> CORE = List.of("reference", "applications-500", "budget-256mb");
    // The highest rate a run offers; four generator threads keep up with it on the reference machine.
    static final int MAX_RATE = LADDER.getLast();
    // The rate every scenario fills its history at and starts its climb from: the limit a run finds depends on the state the fill leaves, so
    // scenarios that compare fill alike.
    private static final int FILL_RATE = 5_000;

    // 50 applications of 10 instances, with 200 message types and 50 processing groups each, in 2 GB with 15 minutes of history.
    static final LoadProfile REFERENCE = new LoadProfile(50, 10, 200, 50, 10_000, WINDOW, Duration.ofMinutes(15), 2048 * MEGABYTE);

    static final List<SaturationScenario> ALL = Stream.of(
            Stream.of(new SaturationScenario("reference", "Reference", Sweep.REFERENCE, REFERENCE, ladderFrom(FILL_RATE), STEP)),
            IntStream.of(5, 100, 500).mapToObj(applications -> new SaturationScenario("applications-" + applications, applications + " applications",
                Sweep.APPLICATIONS, shape(applications, REFERENCE.instances(), REFERENCE.types(), REFERENCE.memory()), ladderFrom(FILL_RATE),
                STEP)),
            Stream.of(new SaturationScenario("instances-100", "100 instances each", Sweep.INSTANCES,
                shape(REFERENCE.applications(), 100, REFERENCE.types(), REFERENCE.memory()), ladderFrom(FILL_RATE), STEP)),
            Stream.of(new SaturationScenario("types-1000", "1,000 types each", Sweep.TYPES,
                shape(REFERENCE.applications(), REFERENCE.instances(), 1_000, REFERENCE.memory()), ladderFrom(FILL_RATE), STEP)),
            Stream.of(new SaturationScenario("budget-256mb", "256 MB budget", Sweep.BUDGET,
                shape(REFERENCE.applications(), REFERENCE.instances(), REFERENCE.types(), 256 * MEGABYTE), ladderFrom(FILL_RATE), STEP)),
            Stream.of(new SaturationScenario("profile-standalone", "Standalone guideline", Sweep.PROFILE, LoadProfile.STANDALONE, ladderFrom(FILL_RATE), STEP)))
        .flatMap(scenarios -> scenarios)
        .toList();

    SaturationScenario {
        requireNonNull(id);
        requireNonNull(name);
        requireNonNull(sweep);
        requireNonNull(profile);
        requireNonNull(rates);
        requireNonNull(step);
        if (rates.isEmpty()) {
            throw new IllegalArgumentException("SaturationScenario " + id + " requires at least one rate");
        }
    }

    // The scenarios the comma-separated names select: "core", the reference, 500 applications, and the 256 MB budget, the three that told runs
    // apart in the runs kept, where the others moved with the reference within a few percent; "all"; "quick", the reference and the profiles; a
    // sweep, such as "applications", with the reference it varies; "profiles"; or a scenario's ID, such as "applications-500".
    static List<SaturationScenario> selected(String names) {
        var selected = new LinkedHashSet<SaturationScenario>();
        for (var name : Arrays.stream(names.split(",")).map(String::strip).filter(each -> !each.isEmpty()).toList()) {
            selected.addAll(switch (name) {
                case "core" -> ALL.stream().filter(scenario -> CORE.contains(scenario.id())).toList();
                case "all" -> ALL;
                case "quick" -> ALL.stream().filter(scenario -> scenario.sweep() == Sweep.REFERENCE || scenario.sweep() == Sweep.PROFILE).toList();
                case "profiles" -> ALL.stream().filter(scenario -> scenario.sweep() == Sweep.PROFILE).toList();
                default -> named(name);
            });
        }
        if (selected.isEmpty()) {
            throw new IllegalArgumentException("SaturationScenario selects scenarios by 'core', 'all', 'quick', 'profiles', a sweep " + Arrays.stream(Sweep.values())
                .filter(Sweep::varies).map(Sweep::key).toList() + ", or an ID " + ALL.stream().map(SaturationScenario::id).toList() + "; got '" + names + "'");
        }
        return new ArrayList<>(selected);
    }

    private static List<SaturationScenario> named(String name) {
        var sweep = Arrays.stream(Sweep.values()).filter(each -> each.varies() && each.key().equals(name)).findFirst();
        if (sweep.isPresent()) {
            return ALL.stream().filter(scenario -> scenario.sweep() == Sweep.REFERENCE || scenario.sweep() == sweep.get()).toList();
        }
        return ALL.stream().filter(scenario -> scenario.id().equals(name)).toList();
    }

    // The scenario offering the comma-separated rates instead of its own.
    SaturationScenario withRates(String rates) {
        return new SaturationScenario(id, name, sweep, profile, Arrays.stream(rates.split(",")).map(String::strip).map(Integer::valueOf).toList(), step);
    }

    String description() {
        return "%,d applications × %,d instances, %,d types and %,d processing groups each, %,d MB, %d minutes of history".formatted(profile.applications(),
            profile.instances(), profile.types(), profile.groups(), profile.memory() / MEGABYTE, profile.history().toMinutes());
    }

    int stepSeconds() {
        return Math.toIntExact(step.toSeconds());
    }

    // The reference's window and history at the given size, with a quarter as many processing groups as message types.
    private static LoadProfile shape(int applications, int instances, int types, long memory) {
        return new LoadProfile(applications, instances, types, types / 4, REFERENCE.spansPerSecond(), REFERENCE.window(), REFERENCE.history(), memory);
    }

    private static List<Integer> ladderFrom(int first) {
        if (!LADDER.contains(first)) {
            throw new IllegalArgumentException("SaturationScenario starts on a rate of its ladder " + LADDER + "; got " + first);
        }
        return LADDER.subList(LADDER.indexOf(first), LADDER.size());
    }

    // What a scenario changes of the reference shape; the reference and the profiles change nothing of it.
    enum Sweep {
        REFERENCE("reference"),
        APPLICATIONS("applications"),
        INSTANCES("instances"),
        TYPES("types"),
        BUDGET("budget"),
        PROFILE("profile");

        private final String key;

        Sweep(String key) {
            this.key = key;
        }

        String key() {
            return key;
        }

        boolean varies() {
            return this != REFERENCE && this != PROFILE;
        }
    }
}
