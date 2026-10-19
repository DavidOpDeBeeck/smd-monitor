package app.dodb.smd.monitor.json;

import app.dodb.smd.monitor.hub.TimelineWindow;

import java.util.List;
import java.util.UUID;

import static app.dodb.smd.monitor.json.FrameView.epochMillis;

// The latest started marks first. A mark's marked ancestor is null when no ancestor of it is marked.
public record TimelineWindowView(String hubSession, long sequence, double endMillis, int inSpan, int matching, List<MarkView> marks) {

    public static TimelineWindowView from(UUID hubSession, TimelineWindow window) {
        return new TimelineWindowView(hubSession.toString(), window.sequence(), epochMillis(window.end()), window.inSpan(), window.matching(),
            window.marks().stream().map(MarkView::from).toList());
    }

    public record MarkView(InvocationView invocation, long root, Long markedAncestor) {

        static MarkView from(TimelineWindow.Mark mark) {
            return new MarkView(InvocationView.from(mark.invocation()), mark.root().value(), mark.markedAncestor().map(id -> id.value()).orElse(null));
        }
    }
}
