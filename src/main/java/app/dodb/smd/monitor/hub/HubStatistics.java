package app.dodb.smd.monitor.hub;

import app.dodb.smd.monitor.hub.span.ApplicationName;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

// Every application by name, whose per-second figures end at the given second and cover the window or the history. The session tells
// one run of the hub from the next, as sequence numbers start over with it; the sequence number tells what the hub retains. An instance that sent
// no spans for the retire-after duration has left its application's instances.
public record HubStatistics(UUID session, long sequence, Instant end, Duration window, Duration history, Duration settle, Duration retireAfter,
                            List<ApplicationStatistics> applications) {

    public HubStatistics {
        requireNonNull(session);
        requireNonNull(end);
        requireNonNull(window);
        requireNonNull(history);
        requireNonNull(settle);
        requireNonNull(retireAfter);
        requireNonNull(applications);
    }

    public Optional<ApplicationStatistics> application(ApplicationName name) {
        return applications.stream().filter(application -> application.application().equals(name)).findFirst();
    }
}
