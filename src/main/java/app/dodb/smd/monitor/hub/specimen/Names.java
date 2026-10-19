package app.dodb.smd.monitor.hub.specimen;

import app.dodb.smd.monitor.hub.budget.Footprint;

import java.util.HashMap;
import java.util.Map;

// One instance of each stage, instance, thread, delivery, and exception name of an application, which its specimens share, with the heap they
// take, each counted by its length; owned by the writer and dropped with the application.
final class Names {

    private final Map<Object, Object> interned = new HashMap<>();
    private long bytes;

    @SuppressWarnings("unchecked")
    <T> T intern(T value) {
        return (T) interned.computeIfAbsent(value, key -> {
            bytes += Footprint.name(key);
            return key;
        });
    }

    long bytes() {
        return bytes;
    }
}
