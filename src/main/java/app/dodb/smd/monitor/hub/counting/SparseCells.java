package app.dodb.smd.monitor.hub.counting;

import java.util.Arrays;
import java.util.function.ObjIntConsumer;

// The cells of the keys active in one block, by key ID: an open-addressing table, so a block holds only the keys that counted in it.
final class SparseCells {

    private static final int EMPTY = -1;

    private int[] keys = emptyKeys(8);
    private Cell[] cells = new Cell[8];
    private int size;

    Cell get(int key) {
        var slot = slotOf(key);
        return keys[slot] == key ? cells[slot] : null;
    }

    Cell getOrCreate(int key) {
        var slot = slotOf(key);
        if (keys[slot] == key) {
            return cells[slot];
        }
        if ((size + 1) * 4 > keys.length * 3) {
            grow();
            slot = slotOf(key);
        }
        keys[slot] = key;
        cells[slot] = new Cell();
        size++;
        return cells[slot];
    }

    int size() {
        return size;
    }

    void forEach(ObjIntConsumer<Cell> action) {
        for (var slot = 0; slot < keys.length; slot++) {
            if (keys[slot] != EMPTY) {
                action.accept(cells[slot], keys[slot]);
            }
        }
    }

    // The slot of the key, or the empty slot where it belongs.
    private int slotOf(int key) {
        var mask = keys.length - 1;
        var slot = (key * 0x9E3779B9) >>> 1 & mask;
        while (keys[slot] != EMPTY && keys[slot] != key) {
            slot = slot + 1 & mask;
        }
        return slot;
    }

    private void grow() {
        var oldKeys = keys;
        var oldCells = cells;
        keys = emptyKeys(oldKeys.length * 2);
        cells = new Cell[oldKeys.length * 2];
        for (var slot = 0; slot < oldKeys.length; slot++) {
            if (oldKeys[slot] != EMPTY) {
                var target = slotOf(oldKeys[slot]);
                keys[target] = oldKeys[slot];
                cells[target] = oldCells[slot];
            }
        }
    }

    private static int[] emptyKeys(int length) {
        var keys = new int[length];
        Arrays.fill(keys, EMPTY);
        return keys;
    }
}
