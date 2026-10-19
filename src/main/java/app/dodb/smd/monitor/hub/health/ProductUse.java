package app.dodb.smd.monitor.hub.health;

// What one product of the hub takes of the heap, by the estimates of Footprint, against what the memory budget allows it.
public record ProductUse(long bytes, long limit) {
}
