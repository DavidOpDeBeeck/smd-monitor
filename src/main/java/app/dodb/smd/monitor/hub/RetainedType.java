package app.dodb.smd.monitor.hub;

import java.util.List;

import static java.util.Objects.requireNonNull;

// The retained dispatches of a type at a sequence number: how many, how often each failure type failed them, most frequent first, and the
// latest, newest first.
public record RetainedType(long sequence, ApplicationType type, int retained, List<FailureTypeCount> failureTypes, List<Invocation> latest) {

    public static final int LATEST = 6;

    public RetainedType {
        requireNonNull(type);
        requireNonNull(failureTypes);
        requireNonNull(latest);
    }

    public record FailureTypeCount(String failureType, int count) {

        public FailureTypeCount {
            requireNonNull(failureType);
        }
    }
}
