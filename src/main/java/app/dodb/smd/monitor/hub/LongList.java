package app.dodb.smd.monitor.hub;

import java.util.AbstractList;
import java.util.RandomAccess;

import static java.util.Objects.requireNonNull;

// The counts of histograms summed, which may outgrow an int, kept unboxed: only the readers of the epoch box the counts they read. It owns its
// array, which whoever builds it fills and never touches again.
public final class LongList extends AbstractList<Long> implements RandomAccess {

    private final long[] values;

    public LongList(long[] values) {
        this.values = requireNonNull(values);
    }

    @Override
    public Long get(int index) {
        return values[index];
    }

    @Override
    public int size() {
        return values.length;
    }

    long sum() {
        var sum = 0L;
        for (var value : values) {
            sum += value;
        }
        return sum;
    }

    boolean nonnegative() {
        for (var value : values) {
            if (value < 0) {
                return false;
            }
        }
        return true;
    }

    long at(int index) {
        return values[index];
    }
}
