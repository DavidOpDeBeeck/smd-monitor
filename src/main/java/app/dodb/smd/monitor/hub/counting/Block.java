package app.dodb.smd.monitor.hub.counting;

import app.dodb.smd.monitor.hub.budget.Footprint;

import java.util.function.ObjIntConsumer;

// The counts of the keys active in one second, or in one 10-second bucket once its seconds left the fine region, and the heap they take: a
// cell per key of a timed table, and an int per key of an occurrence table.
final class Block {

    private final SparseCells[] timed = new SparseCells[Table.values().length];
    private final SparseCounts[] occurrences = new SparseCounts[Table.values().length];
    private long bytes = Footprint.BLOCK;

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
        addOccurrences(table, key, 1);
    }

    long bytes() {
        return bytes;
    }

    // The cell of a key of a timed table, or null when it did not count in this block.
    Cell peek(Table table, int key) {
        var cells = timed[table.ordinal()];
        return cells == null ? null : cells.get(key);
    }

    int occurrences(Table table, int key) {
        var counts = occurrences[table.ordinal()];
        return counts == null ? 0 : counts.get(key);
    }

    void forEach(Table table, ObjIntConsumer<Cell> action) {
        var cells = timed[table.ordinal()];
        if (cells != null) {
            cells.forEach(action);
        }
    }

    void forEachOccurrence(Table table, SparseCounts.KeyCount action) {
        var counts = occurrences[table.ordinal()];
        if (counts != null) {
            counts.forEach(action);
        }
    }

    void add(Block other) {
        for (var table : Table.values()) {
            if (table.timed()) {
                other.forEach(table, (cell, key) -> {
                    if (cell(table, key).add(cell)) {
                        bytes += Footprint.HISTOGRAM;
                    }
                });
            } else {
                other.forEachOccurrence(table, (key, count) -> addOccurrences(table, key, count));
            }
        }
    }

    private Cell cell(Table table, int key) {
        var cells = timed[table.ordinal()];
        if (cells == null) {
            cells = new SparseCells();
            timed[table.ordinal()] = cells;
            bytes += Footprint.BLOCK_TABLE;
        }
        var size = cells.size();
        var cell = cells.getOrCreate(key);
        if (cells.size() != size) {
            bytes += Footprint.CELL;
        }
        return cell;
    }

    private void addOccurrences(Table table, int key, int count) {
        var counts = occurrences[table.ordinal()];
        if (counts == null) {
            counts = new SparseCounts();
            occurrences[table.ordinal()] = counts;
            bytes += Footprint.BLOCK_TABLE;
        }
        if (counts.add(key, count)) {
            bytes += Footprint.OCCURRENCE;
        }
    }
}
