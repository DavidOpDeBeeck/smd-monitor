package app.dodb.smd.monitor.hub.join;

// The children of an application that left the span index without their parent (J5): once their parent did not arrive for another horizon,
// as never received, or when it arrived after them, as after the horizon; the hub did not count their links.
public record Unlinked(long parentNeverReceived, long parentAfterHorizon) {

    public static final Unlinked NONE = new Unlinked(0, 0);

    public Unlinked {
        if (parentNeverReceived < 0 || parentAfterHorizon < 0) {
            throw new IllegalArgumentException("Unlinked requires counts of at least 0; got " + parentNeverReceived + " and " + parentAfterHorizon);
        }
    }
}
