package app.dodb.smd.monitor.hub.web;

import app.dodb.smd.monitor.json.Frame;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static java.util.Objects.requireNonNull;

// The frame of one published epoch as clients receive it (W2, W3): for a client that received one of the frames published just before it, the
// changes since that one, and the whole snapshot for any other. A client still sending a frame skips the next; the changes since the frame it
// holds, about a tenth of a snapshot, let it catch up, where a snapshot would keep it behind. Each message is written as UTF-8 at most once, when
// a client first needs it, and shared by every client. The publisher and the threads of connecting clients read it at once.
public final class PublishedFrame {

    // The frames before it a client may have received and still receive the changes since: a client that skipped up to three frames.
    static final int BASES = 4;

    private final Frame frame;
    // The frames published before it, newest first, at most BASES.
    private final List<Frame> bases;
    private final ObjectMapper mapper;
    private byte[] snapshot;
    private final Map<Long, byte[]> deltas = new HashMap<>();

    PublishedFrame(Frame frame, List<Frame> bases, ObjectMapper mapper) {
        this.frame = requireNonNull(frame);
        this.bases = requireNonNull(bases);
        this.mapper = requireNonNull(mapper);
    }

    public Frame frame() {
        return frame;
    }

    public long epoch() {
        return frame.epoch();
    }

    // The message for a client that last received the frame of the given epoch, as UTF-8 JSON: the changes since that frame when it is one of
    // the bases, the snapshot otherwise.
    public byte[] messageFor(long receivedEpoch) {
        return bases.stream().filter(base -> base.epoch() == receivedEpoch).findFirst().map(this::delta).orElseGet(this::snapshot);
    }

    // The bases of the frame published after this one: this one, then its own, newest first.
    List<Frame> basesOfNext() {
        return Stream.concat(Stream.of(frame), bases.stream()).limit(BASES).toList();
    }

    private synchronized byte[] snapshot() {
        if (snapshot == null) {
            snapshot = mapper.writeValueAsBytes(frame.snapshot());
        }
        return snapshot;
    }

    private synchronized byte[] delta(Frame base) {
        return deltas.computeIfAbsent(base.epoch(), ignored -> mapper.writeValueAsBytes(frame.deltaFrom(base, mapper)));
    }
}
