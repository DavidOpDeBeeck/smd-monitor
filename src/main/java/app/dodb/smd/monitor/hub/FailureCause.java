package app.dodb.smd.monitor.hub;

import app.dodb.smd.monitor.hub.span.ApplicationName;
import app.dodb.smd.monitor.hub.span.Stage;

import java.util.List;

import static java.util.Objects.requireNonNull;

// The retained occurrences of a failure cause, the failures of one stage with one exception type: how many the hub retains, the latest of them
// newest first, and the most frequent chains of the failures they took part in. A chain leads from the entry point the failure reached to the
// invocation that raised it, through the parents it propagated to, by the FailureChains heuristic; the cause is one of its steps.
public record FailureCause(Step cause, int retained, List<Invocation> latest, List<Chain> chains) {

    public static final int LATEST = 6;
    public static final int CHAINS = 10;

    public FailureCause {
        requireNonNull(cause);
        requireNonNull(latest);
        requireNonNull(chains);
    }

    public record Step(ApplicationName application, Stage stage, String failureType) {

        public Step {
            requireNonNull(application);
            requireNonNull(stage);
            requireNonNull(failureType);
        }
    }

    public record Chain(List<Step> steps, int occurrences) {

        public Chain {
            requireNonNull(steps);
        }
    }
}
