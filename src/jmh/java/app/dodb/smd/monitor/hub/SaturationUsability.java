package app.dodb.smd.monitor.hub;

import java.util.ArrayList;
import java.util.List;

import static java.util.Objects.requireNonNull;

// Whether the open UIs of one step of a saturation run with viewers were usable, over the step's seconds after it settled: the hub kept up without
// shedding, the viewers read nearly every frame within a second of the end of the second its epoch closes, and the hub answered nearly every query
// within a second, so even the least lucky viewer's view was fresh after nearly every frame. The run judges each step a few seconds after it ends
// (SaturationRun), and a step that is not usable ends the climb, as the frontier of SaturationCapacity counts usable rates only.
record SaturationUsability(int rate, SaturationStep.Verdict ingest, double framesRead, double frameLagP99Millis, double queriesAnswered,
                           double answerP99Millis, double freshWorst) {

    static final String HEADER = "| Rate | Ingest | Frames read | Frame lag p99 ms | Queries answered | Answer p99 ms | Least lucky viewer fresh | Usable |";
    static final String SEPARATOR = "|---".repeat(8) + "|";

    static final double FRAMES_READ = 0.99;
    static final double FRAME_LAG_MILLIS = 1_000;
    static final double QUERIES_ANSWERED = 0.99;
    static final double ANSWER_MILLIS = 1_000;
    static final double FRESH_WORST = 0.95;

    SaturationUsability {
        requireNonNull(ingest);
    }

    // What fell short, none when the step was usable.
    List<String> shortfalls() {
        var shortfalls = new ArrayList<String>();
        if (ingest != SaturationStep.Verdict.KEEPS_UP) {
            shortfalls.add("ingest " + ingest);
        }
        if (framesRead < FRAMES_READ) {
            shortfalls.add("frames read %s".formatted(percent(framesRead)));
        }
        if (frameLagP99Millis > FRAME_LAG_MILLIS) {
            shortfalls.add("frame lag p99 %,.0f ms".formatted(frameLagP99Millis));
        }
        if (queriesAnswered < QUERIES_ANSWERED) {
            shortfalls.add("queries answered %s".formatted(percent(queriesAnswered)));
        }
        if (answerP99Millis > ANSWER_MILLIS) {
            shortfalls.add("answer p99 %,.0f ms".formatted(answerP99Millis));
        }
        if (freshWorst < FRESH_WORST) {
            shortfalls.add("least lucky viewer fresh %s".formatted(percent(freshWorst)));
        }
        return shortfalls;
    }

    boolean usable() {
        return shortfalls().isEmpty();
    }

    String row() {
        return "| %,d | %s | %s | %,.0f | %s | %,.0f | %s | %s |".formatted(rate, ingest, percent(framesRead), frameLagP99Millis, percent(queriesAnswered),
            answerP99Millis, percent(freshWorst), usable() ? "yes" : "no: " + String.join(", ", shortfalls()));
    }

    String line() {
        return "%,d spans/s: %s".formatted(rate, usable() ? "the viewers' UIs are usable" : "the viewers' UIs are not usable, " + String.join(", ", shortfalls()));
    }

    private static String percent(double share) {
        return "%.1f%%".formatted(100 * share);
    }
}
