package app.dodb.smd.monitor.hub.budget;

// Max-min fair sharing of an allowance among applications (M3): each is entitled to an equal share and may borrow beyond it while others use
// less. Under pressure the application using the most gives back first, so none is pushed below its share while another holds more than its own.
public final class FairShare {

    private FairShare() {
    }

    public static long of(long allowance, int applications) {
        return allowance / Math.max(1, applications);
    }
}
