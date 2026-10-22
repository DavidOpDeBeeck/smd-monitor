package app.dodb.smd.monitor.hub.web;

import app.dodb.smd.monitor.json.Frame;
import tools.jackson.databind.ObjectMapper;

import static java.util.Objects.requireNonNull;

// The frame of one published epoch as clients receive it (W3): the changes since the epoch published before it, for a client that received
// that one, and the whole snapshot for any other. Each is written at most once, when a client first needs it, and shared by every client.
// The publisher and the threads of connecting clients read it at once.
public final class PublishedFrame {

    private final Frame frame;
    private final Frame base;
    private final ObjectMapper mapper;
    private String snapshot;
    private String delta;

    PublishedFrame(Frame frame, Frame base, ObjectMapper mapper) {
        this.frame = requireNonNull(frame);
        this.base = base;
        this.mapper = requireNonNull(mapper);
    }

    public Frame frame() {
        return frame;
    }

    public long epoch() {
        return frame.epoch();
    }

    // The message for a client that last received the frame of the given epoch: the changes when that is the base, the snapshot otherwise.
    public String messageFor(long receivedEpoch) {
        return base != null && base.epoch() == receivedEpoch ? delta() : snapshot();
    }

    private synchronized String snapshot() {
        if (snapshot == null) {
            snapshot = mapper.writeValueAsString(frame.snapshot());
        }
        return snapshot;
    }

    private synchronized String delta() {
        if (delta == null) {
            delta = mapper.writeValueAsString(frame.deltaFrom(base, mapper));
        }
        return delta;
    }
}
