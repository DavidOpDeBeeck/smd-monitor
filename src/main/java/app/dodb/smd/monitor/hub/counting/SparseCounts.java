package app.dodb.smd.monitor.hub.counting;

import java.util.Arrays;

// The counts of the keys of an occurrence table active in one block, by key ID: an open-addressing table of ints, so a block holds only the keys
// that counted in it, and a key holds no more than its count.
final class SparseCounts {

    private static final int EMPTY = -1;

    private int[] keys = emptyKeys(8);
    private int[] counts = new int[8];
    private int size;

    int get(int key) {
        var slot = slotOf(key);
        return keys[slot] == key ? counts[slot] : 0;
    }

    // True when the key is new to the block.
    boolean add(int key, int count) {
        var slot = slotOf(key);
        if (keys[slot] == key) {
            counts[slot] += count;
            return false;
        }
        if ((size + 1) * 4 > keys.length * 3) {
            grow();
            slot = slotOf(key);
        }
        keys[slot] = key;
        counts[slot] = count;
        size++;
        return true;
    }

    void forEach(KeyCount action) {
        for (var slot = 0; slot < keys.length; slot++) {
            if (keys[slot] != EMPTY) {
                action.accept(keys[slot], counts[slot]);
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
        var oldCounts = counts;
        keys = emptyKeys(oldKeys.length * 2);
        counts = new int[oldKeys.length * 2];
        for (var slot = 0; slot < oldKeys.length; slot++) {
            if (oldKeys[slot] != EMPTY) {
                var target = slotOf(oldKeys[slot]);
                keys[target] = oldKeys[slot];
                counts[target] = oldCounts[slot];
            }
        }
    }

    private static int[] emptyKeys(int length) {
        var keys = new int[length];
        Arrays.fill(keys, EMPTY);
        return keys;
    }

    @FunctionalInterface
    interface KeyCount {

        void accept(int key, int count);
    }
}
