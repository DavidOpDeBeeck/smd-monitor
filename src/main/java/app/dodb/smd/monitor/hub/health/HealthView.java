package app.dodb.smd.monitor.hub.health;

import app.dodb.smd.monitor.hub.span.ApplicationName;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

// The health of the hub at one epoch (FR7): its memory against its budget, the history its counts cover against the configured history and
// whether seconds fold sooner under memory pressure (A8), the configured link horizon the applications' effective horizons fall short of, the
// spans of applications beyond its limit, and the health of every application.
public record HealthView(MemoryUse memory, Duration history, Duration effectiveHistory, boolean foldsSooner, Duration linkHorizon, long refusedByApplicationLimit,
                         List<ApplicationHealth> applications) {

    public HealthView {
        requireNonNull(memory);
        requireNonNull(history);
        requireNonNull(effectiveHistory);
        requireNonNull(linkHorizon);
        requireNonNull(applications);
    }

    public Optional<ApplicationHealth> application(ApplicationName name) {
        return applications.stream().filter(application -> application.application().equals(name)).findFirst();
    }
}
