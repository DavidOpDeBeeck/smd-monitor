package app.dodb.smd.monitor.hub;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static java.util.Objects.requireNonNull;

// A clock that stands still at what its owner skips it to until it starts, then runs at the speed of real time, so a saturation run fills the
// hub's history as fast as the hub takes it, without the hub's time running ahead of the load, and then measures at real time.
final class PacedClock extends Clock {

    // Written by the thread that fills the hub and starts the clock, read by the hub's writer and the threads that offer batches.
    private volatile Instant base;
    private volatile long startNanos;
    private volatile boolean running;

    PacedClock(Instant origin) {
        this.base = requireNonNull(origin);
    }

    // Moves the clock ahead to the instant while it stands still.
    void skipTo(Instant target) {
        if (running) {
            throw new IllegalStateException("PacedClock skips only before it starts; asked to skip to " + target);
        }
        if (target.isAfter(base)) {
            base = target;
        }
    }

    void start() {
        startNanos = System.nanoTime();
        running = true;
    }

    // The value of System.nanoTime() at which the running clock reads the instant.
    long nanoTimeAt(Instant at) {
        if (!running) {
            throw new IllegalStateException("PacedClock maps instants to nanoTime only once it runs; asked for " + at);
        }
        return startNanos + Duration.between(base, at).toNanos();
    }

    @Override
    public Instant instant() {
        return running ? base.plusNanos(System.nanoTime() - startNanos) : base;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}
