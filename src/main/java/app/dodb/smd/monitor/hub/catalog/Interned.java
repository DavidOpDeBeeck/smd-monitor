package app.dodb.smd.monitor.hub.catalog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Dense IDs for the keys of one kind, in the order first seen, from the first ID after the reserved ones; beyond the cap, a key gets the
// given other ID.
final class Interned<K> {

    private final int cap;
    private final int reserved;
    private final Map<K, Integer> ids = new HashMap<>();
    private final List<K> keys = new ArrayList<>();

    Interned(int cap, int reserved) {
        this.cap = cap;
        this.reserved = reserved;
    }

    int idOf(K key, int other) {
        var id = ids.get(key);
        if (id != null) {
            return id;
        }
        if (keys.size() >= cap) {
            return other;
        }
        keys.add(key);
        ids.put(key, reserved + keys.size() - 1);
        return reserved + keys.size() - 1;
    }

    // The IDs of the keys run from the first after the reserved ones up to this one, exclusive.
    int end() {
        return reserved + keys.size();
    }

    int size() {
        return keys.size();
    }

    int first() {
        return reserved;
    }

    K key(int id) {
        return keys.get(id - reserved);
    }
}
