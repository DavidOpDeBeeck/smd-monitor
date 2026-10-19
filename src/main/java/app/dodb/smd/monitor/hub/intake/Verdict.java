package app.dodb.smd.monitor.hub.intake;

import java.util.Optional;

import static java.util.Objects.requireNonNull;

// What admission decided for a batch.
public sealed interface Verdict {

    // The spans and sends admission let through, which may be none, and the refusal of those that started too far ahead of the hub's clock.
    record Admitted(SpanBatch batch, Optional<Refusal> refusal) implements Verdict {

        public Admitted {
            requireNonNull(batch);
            requireNonNull(refusal);
        }
    }

    // Every span and send of the batch, as its application or instance is beyond the hub's limits.
    record Refused(Refusal refusal) implements Verdict {

        public Refused {
            requireNonNull(refusal);
        }
    }
}
