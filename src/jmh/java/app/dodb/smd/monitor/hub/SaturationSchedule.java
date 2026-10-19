package app.dodb.smd.monitor.hub;

import java.util.ArrayList;
import java.util.List;

import static java.util.Objects.requireNonNull;

// The rate each second after the fill offers: the scenario's rates in order, each for a step, which the run may end early. Seconds count from 0,
// the first one offered after the fill. The generators ask for a second's rate seconds before it is offered, each for its own applications, so a
// second's step is fixed once any generator asked for it, and an early end falls no sooner than the last second asked for; the last rate keeps
// its whole step.
final class SaturationSchedule {

    private final List<Integer> rates;
    private final int stepSeconds;
    private final List<Long> firstSeconds = new ArrayList<>(List.of(0L));
    private long lastSecond;
    private long askedThrough = -1;

    SaturationSchedule(List<Integer> rates, int stepSeconds) {
        this.rates = requireNonNull(rates);
        this.stepSeconds = stepSeconds;
        this.lastSecond = stepSeconds - 1;
    }

    // The fill, before the first second, offers the first rate; past the last step, the last rate goes on.
    synchronized int rateAt(long second) {
        if (second < 0) {
            return rates.getFirst();
        }
        askedThrough = Math.max(askedThrough, second);
        while (second > lastSecond && firstSeconds.size() < rates.size()) {
            firstSeconds.add(lastSecond + 1);
            lastSecond += stepSeconds;
        }
        return rates.get(stepAt(second));
    }

    // The step of a second a generator asked for.
    synchronized int stepAt(long second) {
        var step = firstSeconds.size() - 1;
        while (step > 0 && firstSeconds.get(step) > second) {
            step--;
        }
        return step;
    }

    synchronized long firstSecondOf(int step) {
        return firstSeconds.get(step);
    }

    synchronized long lastSecondOf(int step) {
        return step + 1 < firstSeconds.size() ? firstSeconds.get(step + 1) - 1 : lastSecond;
    }

    // Ends the latest step after the given second, or after the last second a generator asked for when that is later.
    synchronized void endStepAfter(int step, long second) {
        if (step != firstSeconds.size() - 1 || step == rates.size() - 1) {
            throw new IllegalStateException("SaturationSchedule ends only the latest step before the last of " + rates.size() + " rates, step "
                + (firstSeconds.size() - 1) + "; got step " + step);
        }
        lastSecond = Math.min(lastSecond, Math.max(second, askedThrough));
    }
}
