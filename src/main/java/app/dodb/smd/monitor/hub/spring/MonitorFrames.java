package app.dodb.smd.monitor.hub.spring;

import app.dodb.smd.monitor.hub.Epoch;
import app.dodb.smd.monitor.json.Frame;
import app.dodb.smd.monitor.json.FrameView;
import tools.jackson.databind.ObjectMapper;

import java.util.OptionalLong;

class MonitorFrames {

    // The hub's own mapper, so the application's Jackson settings never change the wire format the page reads.
    private final ObjectMapper mapper = new ObjectMapper();

    // The frame of an epoch, published after the given one, which may be absent: what every page receives, whole or as the changes since.
    PublishedFrame publish(Epoch epoch, PublishedFrame previous) {
        var frame = new Frame(epoch.sequence(), FrameView.from(epoch.at(), epoch.statistics(), epoch.health(), maxHeapBytes()));
        return new PublishedFrame(frame, previous == null ? null : previous.frame(), mapper);
    }

    private static OptionalLong maxHeapBytes() {
        var maxMemory = Runtime.getRuntime().maxMemory();
        return maxMemory == Long.MAX_VALUE ? OptionalLong.empty() : OptionalLong.of(maxMemory);
    }
}
