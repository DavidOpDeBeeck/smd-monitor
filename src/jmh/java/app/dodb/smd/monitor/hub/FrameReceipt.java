package app.dodb.smd.monitor.hub;

// A frame one client of a saturation run read to its end: the epoch it named, whether it was a snapshot, its length in characters, and when its
// last part was read.
record FrameReceipt(int client, boolean slow, long epoch, boolean snapshot, long chars, long receivedNanos) {
}
