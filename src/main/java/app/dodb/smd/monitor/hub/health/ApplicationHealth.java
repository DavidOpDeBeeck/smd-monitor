package app.dodb.smd.monitor.hub.health;

import app.dodb.smd.monitor.hub.intake.Shedding;
import app.dodb.smd.monitor.hub.span.ApplicationName;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

// The health of an application on the hub: its counts by reason, the heaviest shedding of its batches in the last second, and what it takes of
// each product against its fair share. It retains every invocation that started after complete-since, which is empty while the hub dropped or
// shed none, and the oldest it retains started at oldest-retained. Its effective horizon is how long the span index keeps its spans, and its K how many traces its reservoirs keep per type and per
// failure.
public record ApplicationHealth(ApplicationName application,
                                Map<HealthReason, Long> reasons,
                                Shedding shedding,
                                long countingBytes,
                                ProductUse join,
                                ProductUse specimens,
                                int retained,
                                Optional<Instant> completeSince,
                                Optional<Instant> oldestRetained,
                                Duration effectiveHorizon,
                                int slowestK,
                                int failuresK) {

    public ApplicationHealth {
        requireNonNull(application);
        requireNonNull(reasons);
        requireNonNull(shedding);
        requireNonNull(join);
        requireNonNull(specimens);
        requireNonNull(completeSince);
        requireNonNull(oldestRetained);
        requireNonNull(effectiveHorizon);
    }

    public long count(HealthReason reason) {
        return reasons.getOrDefault(reason, 0L);
    }
}
