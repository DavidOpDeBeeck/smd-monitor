package app.dodb.smd.monitor.hub;

import java.util.List;

// What the clients of one step of a saturation run read of the frames the publisher wrote for the step's seconds, after it settled. A frame's lag
// runs from the end of the second its epoch closes, the second before the writer's tick, to when a client read the frame's last part; its send
// runs from when the publisher had written the message. Lag and send count the frames read, not the ones a client missed: the received figure
// tells those, and a client that missed a frame reads a snapshot next.
record SaturationDelivery(int rate, long published, Clients fast, Clients slow) {

    static final String HEADER = "| Rate | Frames | Received | Snapshots | Lag p50, p99, max ms | Send p50, p99 ms | Slow: received | Slow: snapshots "
        + "| Slow: lag p50, max ms |";
    static final String SEPARATOR = "|---".repeat(9) + "|";

    static SaturationDelivery of(int rate, long published, FrameClients.Audience clients, List<Read> reads) {
        return new SaturationDelivery(rate, published, Clients.of(clients.fast(), published, reads.stream().filter(read -> !read.slow()).toList()),
            Clients.of(clients.slow(), published, reads.stream().filter(Read::slow).toList()));
    }

    String row() {
        var slowFigures = slow.count() == 0 ? " - | - | - |"
            : " %s | %,d | %,.0f, %,.0f |".formatted(slow.receivedOf(), slow.snapshots(), slow.lagP50Millis(), slow.lagMaxMillis());
        return "| %,d | %,d | %s | %,d | %,.0f, %,.0f, %,.0f | %,.0f, %,.0f |".formatted(rate, published, fast.receivedOf(), fast.snapshots(),
            fast.lagP50Millis(), fast.lagP99Millis(), fast.lagMaxMillis(), fast.sendP50Millis(), fast.sendP99Millis()) + slowFigures;
    }

    // A frame of the step one client read: whether the client is slow, whether the frame was a snapshot, its lag, and its send.
    record Read(boolean slow, boolean snapshot, long lagNanos, long sendNanos) {
    }

    // The frames clients of one kind read of those published, as a total over the clients.
    record Clients(int count, long expected, long received, long snapshots, double lagP50Millis, double lagP99Millis, double lagMaxMillis,
                   double sendP50Millis, double sendP99Millis) {

        private static Clients of(int count, long published, List<Read> reads) {
            return new Clients(count, count * published, reads.size(), reads.stream().filter(Read::snapshot).count(),
                SaturationRun.percentile(reads, Read::lagNanos, 0.5) / 1e6, SaturationRun.percentile(reads, Read::lagNanos, 0.99) / 1e6,
                SaturationRun.percentile(reads, Read::lagNanos, 1.0) / 1e6, SaturationRun.percentile(reads, Read::sendNanos, 0.5) / 1e6,
                SaturationRun.percentile(reads, Read::sendNanos, 0.99) / 1e6);
        }

        private String receivedOf() {
            return "%,d of %,d".formatted(received, expected);
        }
    }
}
