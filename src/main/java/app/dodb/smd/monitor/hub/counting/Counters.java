package app.dodb.smd.monitor.hub.counting;

import app.dodb.smd.monitor.hub.Counts;
import app.dodb.smd.monitor.hub.HubConfig;
import app.dodb.smd.monitor.hub.IntList;
import app.dodb.smd.monitor.hub.LatencyHistogram;
import app.dodb.smd.monitor.hub.budget.Footprint;
import app.dodb.smd.monitor.hub.span.MessageKind;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;

import static java.util.Objects.requireNonNull;

// The counts of one application, owned by the hub's writer. Every count lands in the start second of its span (T1). The window ends settle
// seconds before the hub's clock, and the history ends with it (T2); a span that arrives for a second that already settled revises it (T3), and
// one that started before the history is not counted (T5). Seconds are kept one by one over the window, the settle, and how far a span may
// start ahead of the clock; older seconds fold into 10-second buckets, so the history totals move in 10-second steps. The totals over the
// window and the history are kept as counts arrive and seconds leave, so reading a key costs the same whatever the history. Under memory
// pressure the seconds beyond the window fold sooner, and then the history shortens a bucket at a time; the window never shrinks (A8).
public final class Counters {

    // How far ahead of the hub's clock admission lets a span start; the rings reach that far.
    private static final int AHEAD = 30;
    private static final int BUCKET = 10;
    private static final long APPLICATION_LAG_PERIOD = 30;
    private static final long INSTANCE_LAG_PERIOD = 10;

    private final int window;
    private final int configuredHistory;
    private int history;
    private final int settle;
    // How many seconds before the window the seconds stay apart before they fold.
    private int beforeFolding = AHEAD;
    private final int sparkLength;
    private final int activityLength;
    private long now;
    private final TreeMap<Long, Block> seconds = new TreeMap<>();
    private final TreeMap<Long, Block> buckets = new TreeMap<>();
    private Totals windowTotals = new Totals();
    private Totals historyTotals = new Totals();
    // The started and failed dispatches of each stage per second, by the second's slot, over the window and as far as a span may start ahead.
    private int[][] sparkStarted = new int[16][];
    private int[][] sparkFailed = new int[16][];
    // The started dispatches per kind, the entry points, and the failures of the application per second, by slot, over the history.
    private final int[][] startedByKind;
    private final int[] entered;
    private final int[] failed;
    private final ArrivalLag arrival = new ArrivalLag(APPLICATION_LAG_PERIOD);
    private ArrivalLag[] exportLags = new ArrivalLag[8];
    private long revised;
    private long beyondHistory;
    // The series of every length that counted nothing, which the statistics of every idle key share; a series is immutable once built.
    private final IntList[] zeros;

    public Counters(HubConfig config, Instant now) {
        requireNonNull(config);
        this.window = (int) config.window().toSeconds();
        this.configuredHistory = (int) config.history().toSeconds();
        this.history = configuredHistory;
        this.settle = (int) config.settle().toSeconds();
        this.sparkLength = window + settle + AHEAD + 1;
        this.activityLength = history + settle + AHEAD + 1;
        this.startedByKind = new int[MessageKind.values().length][activityLength];
        this.entered = new int[activityLength];
        this.failed = new int[activityLength];
        this.zeros = new IntList[activityLength + 1];
        this.now = now.getEpochSecond();
    }

    // Moves the window and the history to the given instant, second by second; a gap longer than everything kept starts afresh.
    public void advance(Instant instant) {
        var target = instant.getEpochSecond();
        if (target - now > (long) configuredHistory + window + settle + AHEAD + BUCKET) {
            clear(target);
            return;
        }
        while (now < target) {
            step();
        }
    }

    public void countInvocation(CountedInvocation invocation) {
        var second = invocation.startedAt().getEpochSecond();
        if (beyondHistory(second)) {
            return;
        }
        var nanos = invocation.duration().toNanos();
        var failure = invocation.failure().isPresent();
        countInvocation(Table.STAGE, invocation.stage(), second, invocation.entry(), failure, nanos);
        countInvocation(invocation.dispatched().isPresent() ? Table.INSTANCE_DISPATCH : Table.INSTANCE_HANDLING, invocation.instance(), second, invocation.entry(),
            failure, nanos);
        invocation.failure().ifPresent(cause -> countOccurrence(Table.FAILURE, cause, second));
        if (second >= horizon() - activityLength + 1 && second <= horizon()) {
            var slot = Math.floorMod(second, activityLength);
            invocation.dispatched().ifPresent(kind -> startedByKind[kind.ordinal()][slot]++);
            entered[slot] += invocation.entry() ? 1 : 0;
            failed[slot] += failure ? 1 : 0;
        }
        if (invocation.dispatched().isPresent() && second >= horizon() - sparkLength + 1 && second <= horizon()) {
            var slot = Math.floorMod(second, sparkLength);
            sparkline(invocation.stage(), true)[slot]++;
            sparkline(invocation.stage(), false)[slot] += failure ? 1 : 0;
        }
    }

    // A link, route, or group link, counted in the start second of its child.
    public void countOccurrence(Table table, int key, Instant at) {
        var second = at.getEpochSecond();
        if (second >= historyStart()) {
            countOccurrence(table, key, second);
        }
    }

    public void countSend(int sinkRoute, Instant startedAt) {
        var second = startedAt.getEpochSecond();
        if (!beyondHistory(second)) {
            countOccurrence(Table.SINK_ROUTE, sinkRoute, second);
        }
    }

    public void countDelay(int stage, Instant startedAt, Duration delay) {
        var second = startedAt.getEpochSecond();
        if (second < historyStart()) {
            return;
        }
        var nanos = delay.toNanos();
        block(second).countDuration(Table.DELAY, stage, nanos);
        if (second < end()) {
            historyTotals.countDuration(Table.DELAY, stage, nanos);
            if (second >= windowStart()) {
                windowTotals.countDuration(Table.DELAY, stage, nanos);
            }
        }
    }

    // How long the span took to reach the hub after it started, for the application (T4), and after it ended, for its instance (T7).
    public void recordArrival(Instant arrival, Instant startedAt, Duration duration, int instance) {
        var second = arrival.getEpochSecond();
        this.arrival.record(second, Duration.between(startedAt, arrival).toNanos());
        exportLagOf(instance).record(second, Duration.between(startedAt.plus(duration), arrival).toNanos());
    }

    // The window and the history end before this second.
    public long end() {
        return now - settle;
    }

    // The history the counts cover: the configured history, or less under memory pressure.
    public Duration history() {
        return Duration.ofSeconds(history);
    }

    // The heap the counts take: the seconds and buckets, the running totals, and the series of the sparklines, the Activity chart, and the lags.
    public long bytes() {
        var blocks = 0L;
        for (var block : seconds.values()) {
            blocks += block.bytes();
        }
        for (var block : buckets.values()) {
            blocks += block.bytes();
        }
        var sparklines = 0L;
        for (var stage = 0; stage < sparkStarted.length; stage++) {
            sparklines += (sparkStarted[stage] == null ? 0 : 2) * Footprint.ints(sparkLength);
        }
        var activity = (startedByKind.length + 2) * Footprint.ints(activityLength);
        return blocks + windowTotals.bytes() + historyTotals.bytes() + sparklines + activity;
    }

    // Under memory pressure, folds the seconds before the window into their buckets at once and every second from then on, and covers the given
    // history, between the window and the configured history; the oldest counts beyond it go. A longer history refills as seconds pass (A8).
    public void limit(boolean foldSooner, Duration history) {
        if (foldSooner && beforeFolding > 0) {
            beforeFolding = 0;
            var folding = seconds.headMap(fineStart());
            folding.forEach((second, block) -> buckets.computeIfAbsent(Math.floorDiv(second, BUCKET) * BUCKET, ignored -> new Block()).add(block));
            folding.clear();
        }
        if (!foldSooner) {
            beforeFolding = AHEAD;
        }
        this.history = (int) Math.clamp(history.toSeconds(), window, configuredHistory);
        while (!buckets.isEmpty() && buckets.firstKey() + BUCKET <= historyStart()) {
            historyTotals.subtract(buckets.pollFirstEntry().getValue());
        }
        var leaving = seconds.headMap(historyStart());
        leaving.values().forEach(historyTotals::subtract);
        leaving.clear();
    }

    // The seconds from this one to the end of the window may still grow, as spans of the application arrive this late (T4).
    public long provisionalFrom() {
        var lag = arrival.p99(now);
        if (lag.isEmpty()) {
            return end();
        }
        var lagSeconds = (lag.get().toNanos() + 999_999_999) / 1_000_000_000;
        return Math.max(windowStart(), Math.min(end(), now - lagSeconds));
    }

    public Counts window(Table table, int key) {
        refreshWindowMaximum(table, key);
        return counts(windowTotals, table, key);
    }

    public Counts history(Table table, int key) {
        refreshHistoryMaximum(table, key);
        return counts(historyTotals, table, key);
    }

    // The window totals of the keys summed, in one pass.
    public Counts windowSum(Table table, int[] keys) {
        var sum = new Cell();
        for (var key : keys) {
            var total = windowTotals.peek(table, key);
            if (total != null) {
                refreshWindowMaximum(table, key);
                sum.add(total);
            }
        }
        return sum.counts();
    }

    public LatencyHistogram windowLatency(Table table, int key) {
        refreshWindowMaximum(table, key);
        var total = windowTotals.peek(table, key);
        return total == null ? Counts.NONE.latency() : total.histogram();
    }

    // How many the key counted over the window: the invocations it started, or its occurrences.
    public long windowCount(Table table, int key) {
        return count(windowTotals, table, key);
    }

    public long historyCount(Table table, int key) {
        return count(historyTotals, table, key);
    }

    // The occurrences of every key of an untimed table below the end in each second of the window, by key, read in one pass over the window's
    // seconds; a key that counted none in the window has no seconds.
    public List<List<Long>> windowPerSecond(Table table, int end) {
        var values = new int[end][];
        var start = windowStart();
        for (var second : seconds.subMap(start, end()).entrySet()) {
            var offset = (int) (second.getKey() - start);
            second.getValue().forEachOccurrence(table, (key, count) -> {
                if (key < end) {
                    if (values[key] == null) {
                        values[key] = new int[window];
                    }
                    values[key][offset] += count;
                }
            });
        }
        return Arrays.stream(values).map(row -> row == null ? List.<Long>of() : new IntList(row)).toList();
    }

    // The count of the last second of the window.
    public long lastSecond(Table table, int key) {
        var block = seconds.get(end() - 1);
        if (block == null) {
            return 0;
        }
        if (!table.timed()) {
            return block.occurrences(table, key);
        }
        var cell = block.peek(table, key);
        return cell == null ? 0 : cell.started;
    }

    public List<Long> startedPerSecond(int stage) {
        return series(stage < sparkStarted.length ? sparkStarted[stage] : null, sparkLength, windowStart());
    }

    public List<Long> failedPerSecond(int stage) {
        return series(stage < sparkFailed.length ? sparkFailed[stage] : null, sparkLength, windowStart());
    }

    // The dispatches of the kind that started in each second of the history.
    public List<Long> startedPerSecond(MessageKind kind) {
        return series(startedByKind[kind.ordinal()], activityLength, historyStart());
    }

    // The entry points of each second of the window.
    public List<Long> enteredPerSecond() {
        return series(entered, activityLength, windowStart());
    }

    // The failed invocations of each second of the history.
    public List<Long> failedPerSecond() {
        return series(failed, activityLength, historyStart());
    }

    public Optional<Duration> exportLag(int instance) {
        return instance < exportLags.length && exportLags[instance] != null ? exportLags[instance].p99(now) : Optional.empty();
    }

    // The spans counted in a second that had already settled.
    public long revised() {
        return revised;
    }

    // The spans not counted as they started before the history.
    public long beyondHistory() {
        return beyondHistory;
    }

    private long windowStart() {
        return end() - window;
    }

    private long historyStart() {
        return end() - history;
    }

    private long fineStart() {
        return now - settle - window - beforeFolding;
    }

    private long horizon() {
        return now + AHEAD;
    }

    private boolean beyondHistory(long second) {
        if (second < historyStart()) {
            beyondHistory++;
            return true;
        }
        if (second < end()) {
            revised++;
        }
        return false;
    }

    private void countInvocation(Table table, int key, long second, boolean entry, boolean failure, long nanos) {
        block(second).countInvocation(table, key, entry, failure, nanos);
        if (second < end()) {
            historyTotals.countInvocation(table, key, entry, failure, nanos);
            if (second >= windowStart()) {
                windowTotals.countInvocation(table, key, entry, failure, nanos);
            }
        }
    }

    private void countOccurrence(Table table, int key, long second) {
        block(second).countOccurrence(table, key);
        if (second < end()) {
            historyTotals.countOccurrence(table, key);
            if (second >= windowStart()) {
                windowTotals.countOccurrence(table, key);
            }
        }
    }

    private static long count(Totals totals, Table table, int key) {
        if (!table.timed()) {
            return totals.occurrences(table, key);
        }
        var total = totals.peek(table, key);
        return total == null ? 0 : total.started;
    }

    private Block block(long second) {
        if (second >= fineStart()) {
            return seconds.computeIfAbsent(second, ignored -> new Block());
        }
        return buckets.computeIfAbsent(Math.floorDiv(second, BUCKET) * BUCKET, ignored -> new Block());
    }

    // One second on: the second that settled enters the window and the history, the oldest second of the window leaves it, the oldest second
    // of the history leaves it while still kept on its own, the oldest second kept on its own folds into its bucket, and a bucket that ended
    // before the history leaves it.
    private void step() {
        var settling = end();
        var leavingWindow = windowStart();
        var leavingHistory = historyStart();
        var folding = fineStart();
        now++;
        var settled = seconds.get(settling);
        if (settled != null) {
            historyTotals.add(settled);
            windowTotals.add(settled);
        }
        var leftWindow = seconds.get(leavingWindow);
        if (leftWindow != null) {
            windowTotals.subtract(leftWindow);
        }
        var leftHistory = seconds.remove(leavingHistory);
        if (leftHistory != null) {
            historyTotals.subtract(leftHistory);
        }
        var folded = seconds.remove(folding);
        if (folded != null) {
            buckets.computeIfAbsent(Math.floorDiv(folding, BUCKET) * BUCKET, ignored -> new Block()).add(folded);
        }
        var oldest = buckets.firstEntry();
        if (oldest != null && oldest.getKey() + BUCKET <= historyStart()) {
            buckets.pollFirstEntry();
            historyTotals.subtract(oldest.getValue());
        }
        clearColumn(horizon());
    }

    private void clearColumn(long second) {
        var spark = Math.floorMod(second, sparkLength);
        for (var row : sparkStarted) {
            if (row != null) {
                row[spark] = 0;
            }
        }
        for (var row : sparkFailed) {
            if (row != null) {
                row[spark] = 0;
            }
        }
        var activity = Math.floorMod(second, activityLength);
        for (var row : startedByKind) {
            row[activity] = 0;
        }
        entered[activity] = 0;
        failed[activity] = 0;
    }

    private void clear(long second) {
        now = second;
        seconds.clear();
        buckets.clear();
        windowTotals = new Totals();
        historyTotals = new Totals();
        sparkStarted = new int[sparkStarted.length][];
        sparkFailed = new int[sparkFailed.length][];
        Arrays.stream(startedByKind).forEach(row -> Arrays.fill(row, 0));
        Arrays.fill(entered, 0);
        Arrays.fill(failed, 0);
    }

    // A maximum that left the totals is found again among the blocks that remain.
    private void refreshWindowMaximum(Table table, int key) {
        if (windowTotals.stale(table, key)) {
            refreshMaximum(windowTotals, table, key, seconds.subMap(windowStart(), end()).values());
        }
    }

    private void refreshHistoryMaximum(Table table, int key) {
        if (historyTotals.stale(table, key)) {
            var blocks = new ArrayList<Block>(buckets.values());
            blocks.addAll(seconds.headMap(end()).values());
            refreshMaximum(historyTotals, table, key, blocks);
        }
    }

    private static Counts counts(Totals totals, Table table, int key) {
        var total = totals.peek(table, key);
        return total == null ? Counts.NONE : total.counts();
    }

    private static void refreshMaximum(Totals totals, Table table, int key, Iterable<Block> blocks) {
        var total = totals.peek(table, key);
        total.resetMaximum();
        for (var block : blocks) {
            var cell = block.peek(table, key);
            if (cell != null) {
                total.raiseMaximum(cell);
            }
        }
        totals.fresh(table, key);
    }

    private int[] sparkline(int stage, boolean started) {
        if (stage >= sparkStarted.length) {
            sparkStarted = Arrays.copyOf(sparkStarted, Math.max(stage + 1, sparkStarted.length * 2));
            sparkFailed = Arrays.copyOf(sparkFailed, sparkStarted.length);
        }
        var rows = started ? sparkStarted : sparkFailed;
        if (rows[stage] == null) {
            rows[stage] = new int[sparkLength];
        }
        return rows[stage];
    }

    private ArrivalLag exportLagOf(int instance) {
        if (instance >= exportLags.length) {
            exportLags = Arrays.copyOf(exportLags, Math.max(instance + 1, exportLags.length * 2));
        }
        if (exportLags[instance] == null) {
            exportLags[instance] = new ArrivalLag(INSTANCE_LAG_PERIOD);
        }
        return exportLags[instance];
    }

    private List<Long> series(int[] slots, int length, long from) {
        var size = (int) (end() - from);
        if (slots == null) {
            return zeros(size);
        }
        // The seconds run from the first one's slot to the end of the ring, and wrap to its start.
        var values = new int[size];
        var first = Math.floorMod(from, length);
        var head = Math.min(size, length - first);
        System.arraycopy(slots, first, values, 0, head);
        System.arraycopy(slots, 0, values, head, size - head);
        for (var value : values) {
            if (value != 0) {
                return new IntList(values);
            }
        }
        return zeros(size);
    }

    private IntList zeros(int size) {
        if (zeros[size] == null) {
            zeros[size] = new IntList(new int[size]);
        }
        return zeros[size];
    }
}
