package app.dodb.smd.monitor.hub.intake;

import app.dodb.smd.monitor.hub.span.ApplicationName;

import java.util.Map;

import static java.util.Objects.requireNonNull;

// The spans and sends admission refused since the hub started, by reason: those of applications beyond the hub's limit, and per application
// those of instances beyond its limit and those that started too far ahead of the hub's clock.
public record RefusedSpans(long beyondApplications, Map<ApplicationName, Long> beyondInstances, Map<ApplicationName, Long> aheadOfClock) {

    public RefusedSpans {
        requireNonNull(beyondInstances);
        requireNonNull(aheadOfClock);
    }
}
