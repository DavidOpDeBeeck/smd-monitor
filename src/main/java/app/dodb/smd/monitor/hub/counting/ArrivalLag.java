package app.dodb.smd.monitor.hub.counting;

import java.time.Duration;
import java.util.Optional;

// How long spans took to reach the hub, over the current period and the one before it, so the figure covers recent spans only.
final class ArrivalLag {

    private final long periodSeconds;
    private Cell current = new Cell();
    private Cell previous = new Cell();
    private long periodStart;

    ArrivalLag(long periodSeconds) {
        this.periodSeconds = periodSeconds;
    }

    void record(long nowSecond, long lagNanos) {
        rotate(nowSecond);
        current.countDuration(Math.max(0, lagNanos));
    }

    Optional<Duration> p99(long nowSecond) {
        rotate(nowSecond);
        var recent = new Cell();
        recent.add(previous);
        recent.add(current);
        return recent.histogram().percentile(0.99);
    }

    private void rotate(long nowSecond) {
        if (nowSecond < periodStart + periodSeconds) {
            return;
        }
        previous = nowSecond < periodStart + 2 * periodSeconds ? current : new Cell();
        current = new Cell();
        periodStart = nowSecond - Math.floorMod(nowSecond, periodSeconds);
    }
}
