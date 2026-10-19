package app.dodb.smd.monitor.hub;

import java.time.Duration;

import static java.util.Objects.requireNonNull;

// The shape of a synthetic load: the applications, the instances of each, the message types and processing groups of each, the spans per
// second across the hub, and the window, history, and memory budget the hub keeps.
record LoadProfile(int applications, int instances, int types, int groups, int spansPerSecond, Duration window, Duration history, long memory) {

    // The design target of the Envelope section of docs/monitor-hub-design.md, with its guideline budget.
    static final LoadProfile STANDALONE = new LoadProfile(50, 20, 500, 100, 10_000, Duration.ofMinutes(1), Duration.ofMinutes(15), 2048L * 1024 * 1024);

    LoadProfile {
        requireNonNull(window);
        requireNonNull(history);
        if (applications < 1 || instances < 1 || groups < 1 || spansPerSecond < 1) {
            throw new IllegalArgumentException("LoadProfile requires at least one application, instance, processing group, and span per second; got "
                + applications + " applications, " + instances + " instances, " + groups + " groups, and " + spansPerSecond + " spans per second");
        }
        if (types < 5) {
            throw new IllegalArgumentException("LoadProfile requires at least 5 types per application, so it has commands, events, and queries; got " + types);
        }
    }

    // The hub's defaults, with the window, history, and budget of the profile, and caps that count each of its applications, instances, types, and
    // processing groups apart.
    HubConfig.Builder hubConfig() {
        return HubConfig.withDefaults()
            .memory(memory)
            .window(window)
            .history(history)
            .maxApplications(Math.max(HubConfig.DEFAULT_MAX_APPLICATIONS, applications))
            .maxInstances(Math.max(HubConfig.DEFAULT_MAX_INSTANCES, instances))
            .maxTypes(Math.max(HubConfig.DEFAULT_MAX_TYPES, types))
            .maxGroups(Math.max(HubConfig.DEFAULT_MAX_GROUPS, groups));
    }
}
