package app.dodb.smd.monitor.json;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static java.util.Objects.requireNonNull;

// The frame of one epoch as the page mirrors it (W1 to W3): the snapshot of its view, and the messages that carry it. A snapshot message carries
// the whole view; a delta message carries the changes from the frame of its base epoch, and how many seconds the series moved since. Both name
// the protocol, the build, their type, and their epoch.
public final class Frame {

    private final long epoch;
    private final FrameView view;

    public Frame(long epoch, FrameView view) {
        this.epoch = epoch;
        this.view = requireNonNull(view);
    }

    public long epoch() {
        return epoch;
    }

    public FrameView view() {
        return view;
    }

    public SnapshotMessage snapshot() {
        return new SnapshotMessage(Protocol.VERSION, Protocol.build(), "snapshot", epoch, null, view);
    }

    public DeltaMessage deltaFrom(Frame base, ObjectMapper mapper) {
        var shift = view.endEpochSecond() - base.view.endEpochSecond();
        return new DeltaMessage(Protocol.VERSION, Protocol.build(), "delta", epoch, base.epoch, shift, FrameDelta.between(base.view, view, shift, mapper));
    }

    public record SnapshotMessage(int protocol, String build, String type, long epoch, Long base, FrameView frame) {
    }

    public record DeltaMessage(int protocol, String build, String type, long epoch, long base, long shift, JsonNode changes) {
    }
}
