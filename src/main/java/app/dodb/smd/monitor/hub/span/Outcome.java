package app.dodb.smd.monitor.hub.span;

import java.time.Duration;

import static java.util.Objects.requireNonNull;

public sealed interface Outcome {

    Duration duration();

    Status status();

    enum Status {
        COMPLETED,
        FAILED
    }

    record Completed(Duration duration) implements Outcome {

        public Completed {
            requireNonNull(duration);
            if (duration.isNegative()) {
                throw new IllegalArgumentException("Completed outcome requires a nonnegative duration; got " + duration);
            }
        }

        @Override
        public Status status() {
            return Status.COMPLETED;
        }
    }

    record Failed(Duration duration, String failureType) implements Outcome {

        public Failed {
            requireNonNull(duration);
            requireNonNull(failureType);
            if (duration.isNegative()) {
                throw new IllegalArgumentException("Failed outcome for " + failureType + " requires a nonnegative duration; got " + duration);
            }
        }

        @Override
        public Status status() {
            return Status.FAILED;
        }
    }
}
