package app.dodb.smd.monitor.hub;

import java.time.Duration;

import static java.time.Duration.ofMinutes;
import static java.time.Duration.ofSeconds;
import static java.util.Objects.requireNonNull;

// The hub's limits. The memory budget bounds everything the hub keeps (TR7). The window ends settle seconds before the hub's clock, and the history ends with it; the link horizon is how long the hub
// keeps a span to link it and to recognize it arriving again; the caps bound the types, processing groups, and edges of each kind an application
// keeps apart, counting further ones under its other keys.
public record HubConfig(long memory,
                        Duration window,
                        Duration history,
                        Duration settle,
                        Duration linkHorizon,
                        int maxApplications,
                        int maxInstances,
                        int maxTypes,
                        int maxGroups,
                        int maxEdges,
                        Duration silentAfter,
                        Duration retireAfter) {

    public static final long DEFAULT_MEMORY = 256L * 1024 * 1024;
    public static final Duration DEFAULT_WINDOW = ofMinutes(1);
    public static final Duration DEFAULT_HISTORY = ofMinutes(5);
    public static final Duration DEFAULT_SETTLE = ofSeconds(5);
    public static final Duration DEFAULT_LINK_HORIZON = ofSeconds(60);
    public static final int DEFAULT_MAX_APPLICATIONS = 100;
    public static final int DEFAULT_MAX_INSTANCES = 256;
    public static final int DEFAULT_MAX_TYPES = 1_000;
    public static final int DEFAULT_MAX_GROUPS = 200;
    public static final int DEFAULT_MAX_EDGES = 5_000;
    public static final Duration DEFAULT_SILENT_AFTER = ofSeconds(10);
    public static final Duration DEFAULT_RETIRE_AFTER = ofMinutes(10);

    static final long MIN_MEMORY = 32L * 1024 * 1024;
    static final long MAX_MEMORY = 8L * 1024 * 1024 * 1024;
    static final Duration MIN_WINDOW = ofSeconds(10);
    static final Duration MAX_HISTORY = ofMinutes(15);
    static final Duration MIN_SETTLE = ofSeconds(1);
    static final Duration MAX_SETTLE = ofSeconds(10);
    static final Duration MIN_LINK_HORIZON = ofSeconds(10);
    static final int MAX_TYPES = 8_000;
    static final int MAX_GROUPS = 2_000;
    static final int MAX_EDGES = 80_000;

    public HubConfig {
        requireNonNull(window);
        requireNonNull(history);
        requireNonNull(settle);
        requireNonNull(linkHorizon);
        requireNonNull(silentAfter);
        requireNonNull(retireAfter);
        if (memory < MIN_MEMORY || memory > MAX_MEMORY) {
            throw new IllegalArgumentException("MonitorHub memory must be between " + MIN_MEMORY + " bytes (32MB) and " + MAX_MEMORY + " bytes (8GB); got " + memory
                + " bytes");
        }
        if (!wholeSecondsBetween(window, MIN_WINDOW, MAX_HISTORY)) {
            throw new IllegalArgumentException("MonitorHub window must be whole seconds between " + MIN_WINDOW.toSeconds() + " and " + MAX_HISTORY.toSeconds()
                + " seconds; got " + window);
        }
        if (!wholeSecondsBetween(history, window, MAX_HISTORY)) {
            throw new IllegalArgumentException("MonitorHub history must be whole seconds between the window of " + window.toSeconds() + " and "
                + MAX_HISTORY.toSeconds() + " seconds; got " + history);
        }
        if (!wholeSecondsBetween(settle, MIN_SETTLE, MAX_SETTLE)) {
            throw new IllegalArgumentException("MonitorHub settle must be whole seconds between " + MIN_SETTLE.toSeconds() + " and " + MAX_SETTLE.toSeconds()
                + " seconds; got " + settle);
        }
        if (!wholeSecondsBetween(linkHorizon, MIN_LINK_HORIZON, history)) {
            throw new IllegalArgumentException("MonitorHub link horizon must be whole seconds between " + MIN_LINK_HORIZON.toSeconds() + " and the history of "
                + history.toSeconds() + " seconds; got " + linkHorizon);
        }
        if (maxApplications < 1) {
            throw new IllegalArgumentException("MonitorHub max applications must be at least 1; got " + maxApplications);
        }
        if (maxInstances < 1) {
            throw new IllegalArgumentException("MonitorHub max instances must be at least 1 per application; got " + maxInstances);
        }
        if (maxTypes < 1 || maxTypes > MAX_TYPES) {
            throw new IllegalArgumentException("MonitorHub max types must be between 1 and " + MAX_TYPES + " per application; got " + maxTypes);
        }
        if (maxGroups < 1 || maxGroups > MAX_GROUPS) {
            throw new IllegalArgumentException("MonitorHub max groups must be between 1 and " + MAX_GROUPS + " per application; got " + maxGroups);
        }
        if (maxEdges < 1 || maxEdges > MAX_EDGES) {
            throw new IllegalArgumentException("MonitorHub max edges must be between 1 and " + MAX_EDGES + " per application and kind; got " + maxEdges);
        }
        if (!silentAfter.isPositive()) {
            throw new IllegalArgumentException("MonitorHub silent-after must be positive; got " + silentAfter);
        }
        if (retireAfter.compareTo(silentAfter) <= 0) {
            throw new IllegalArgumentException("MonitorHub retire-after must be longer than silent-after " + silentAfter + "; got " + retireAfter);
        }
    }

    public static Builder withDefaults() {
        return new Builder();
    }

    private static boolean wholeSecondsBetween(Duration duration, Duration min, Duration max) {
        return duration.toNanosPart() == 0 && duration.compareTo(min) >= 0 && duration.compareTo(max) <= 0;
    }

    public static class Builder {

        private long memory = DEFAULT_MEMORY;
        private Duration window = DEFAULT_WINDOW;
        private Duration history = DEFAULT_HISTORY;
        private Duration settle = DEFAULT_SETTLE;
        private Duration linkHorizon = DEFAULT_LINK_HORIZON;
        private int maxApplications = DEFAULT_MAX_APPLICATIONS;
        private int maxInstances = DEFAULT_MAX_INSTANCES;
        private int maxTypes = DEFAULT_MAX_TYPES;
        private int maxGroups = DEFAULT_MAX_GROUPS;
        private int maxEdges = DEFAULT_MAX_EDGES;
        private Duration silentAfter = DEFAULT_SILENT_AFTER;
        private Duration retireAfter = DEFAULT_RETIRE_AFTER;

        private Builder() {
        }

        public Builder memory(long bytes) {
            this.memory = bytes;
            return this;
        }

        public Builder window(Duration window) {
            this.window = window;
            return this;
        }

        public Builder history(Duration history) {
            this.history = history;
            return this;
        }

        public Builder settle(Duration settle) {
            this.settle = settle;
            return this;
        }

        public Builder linkHorizon(Duration linkHorizon) {
            this.linkHorizon = linkHorizon;
            return this;
        }

        public Builder maxApplications(int maxApplications) {
            this.maxApplications = maxApplications;
            return this;
        }

        public Builder maxInstances(int maxInstances) {
            this.maxInstances = maxInstances;
            return this;
        }

        public Builder maxTypes(int maxTypes) {
            this.maxTypes = maxTypes;
            return this;
        }

        public Builder maxGroups(int maxGroups) {
            this.maxGroups = maxGroups;
            return this;
        }

        public Builder maxEdges(int maxEdges) {
            this.maxEdges = maxEdges;
            return this;
        }

        public Builder silentAfter(Duration silentAfter) {
            this.silentAfter = silentAfter;
            return this;
        }

        public Builder retireAfter(Duration retireAfter) {
            this.retireAfter = retireAfter;
            return this;
        }

        public HubConfig build() {
            return new HubConfig(memory, window, history, settle, linkHorizon, maxApplications, maxInstances, maxTypes, maxGroups, maxEdges, silentAfter,
                retireAfter);
        }
    }
}
