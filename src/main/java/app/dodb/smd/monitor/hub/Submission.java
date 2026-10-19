package app.dodb.smd.monitor.hub;

import app.dodb.smd.monitor.hub.intake.Refusal;
import app.dodb.smd.monitor.hub.span.ApplicationName;

import java.time.Duration;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

// What the hub answers a batch with, before its writer processes it.
public sealed interface Submission {

    // The hub queued the batch for its writer, except the spans of the refusal.
    record Accepted(Optional<Refusal> refusal) implements Submission {

        public Accepted {
            requireNonNull(refusal);
        }
    }

    // The application's intake queue is full; the exporter sends the batch again after the delay.
    record Busy(ApplicationName application, Duration retryAfter) implements Submission {

        public Busy {
            requireNonNull(application);
            requireNonNull(retryAfter);
        }
    }
}
