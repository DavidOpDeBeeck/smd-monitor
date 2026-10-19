package app.dodb.smd.monitor.hub;

import static java.util.Objects.requireNonNull;

// What an application counted beyond its caps: the dispatches and handlings of the types and processing groups it did not keep apart, and
// the edges and failures of each kind beyond theirs, over the window and the history.
public record OtherStatistics(Counts dispatchWindow, Counts dispatchHistory, Counts handlingWindow, Counts handlingHistory, long edgeHistory, long failureHistory) {

    public static final OtherStatistics NONE = new OtherStatistics(Counts.NONE, Counts.NONE, Counts.NONE, Counts.NONE, 0, 0);

    public OtherStatistics {
        requireNonNull(dispatchWindow);
        requireNonNull(dispatchHistory);
        requireNonNull(handlingWindow);
        requireNonNull(handlingHistory);
    }
}
