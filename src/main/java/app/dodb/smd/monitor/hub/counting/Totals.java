package app.dodb.smd.monitor.hub.counting;

import app.dodb.smd.monitor.hub.budget.Footprint;

import java.util.Arrays;
import java.util.BitSet;

// The running totals of every key over the window or over the history: a block's counts are added when its second settles inside, and
// subtracted when it leaves. A timed key totals in a cell, an occurrence key in a long. A subtracted maximum leaves the total's maximum stale
// until it is found again among the remaining blocks.
final class Totals {

    private final Cell[][] cells = new Cell[Table.values().length][16];
    private final long[][] occurrences = new long[Table.values().length][16];
    private final BitSet[] stale = new BitSet[Table.values().length];
    private long bytes;

    Totals() {
        Arrays.setAll(stale, ignored -> new BitSet());
    }

    void countInvocation(Table table, int key, boolean entry, boolean failure, long nanos) {
        if (cell(table, key).countInvocation(entry, failure, nanos)) {
            bytes += Footprint.HISTOGRAM;
        }
    }

    void countDuration(Table table, int key, long nanos) {
        if (cell(table, key).countDuration(nanos)) {
            bytes += Footprint.HISTOGRAM;
        }
    }

    void countOccurrence(Table table, int key) {
        row(table, key)[key]++;
    }

    // The cells and the occurrence totals of the keys that counted, with their slots.
    long bytes() {
        return bytes;
    }

    // The total of a key of a timed table, or null when it never counted.
    Cell peek(Table table, int key) {
        var row = cells[table.ordinal()];
        return key < row.length ? row[key] : null;
    }

    long occurrences(Table table, int key) {
        var row = occurrences[table.ordinal()];
        return key < row.length ? row[key] : 0;
    }

    void add(Block block) {
        for (var table : Table.values()) {
            if (table.timed()) {
                block.forEach(table, (cell, key) -> {
                    if (cell(table, key).add(cell)) {
                        bytes += Footprint.HISTOGRAM;
                    }
                });
            } else {
                block.forEachOccurrence(table, (key, count) -> row(table, key)[key] += count);
            }
        }
    }

    void subtract(Block block) {
        for (var table : Table.values()) {
            if (table.timed()) {
                block.forEach(table, (cell, key) -> {
                    if (cell(table, key).subtract(cell)) {
                        stale[table.ordinal()].set(key);
                    }
                });
            } else {
                block.forEachOccurrence(table, (key, count) -> row(table, key)[key] -= count);
            }
        }
    }

    boolean stale(Table table, int key) {
        return stale[table.ordinal()].get(key);
    }

    void fresh(Table table, int key) {
        stale[table.ordinal()].clear(key);
    }

    private Cell cell(Table table, int key) {
        var row = cells[table.ordinal()];
        if (key >= row.length) {
            row = Arrays.copyOf(row, Math.max(key + 1, row.length * 2));
            cells[table.ordinal()] = row;
        }
        if (row[key] == null) {
            row[key] = new Cell();
            bytes += Footprint.CELL;
        }
        return row[key];
    }

    private long[] row(Table table, int key) {
        var row = occurrences[table.ordinal()];
        if (key >= row.length) {
            var grown = Arrays.copyOf(row, Math.max(key + 1, row.length * 2));
            bytes += (long) (grown.length - row.length) * Long.BYTES;
            row = grown;
            occurrences[table.ordinal()] = row;
        }
        return row;
    }
}
