package app.dodb.smd.monitor.json;

import app.dodb.smd.monitor.hub.InvocationId;
import app.dodb.smd.monitor.hub.TapePage;

import java.util.List;
import java.util.UUID;

import static app.dodb.smd.monitor.json.FrameView.millis;

// Newest first. The slowest is null when nothing matches, and the next page follows the cursor, which is null on the last page.
public record TapePageView(String hubSession, long sequence, List<RetainedInvocationView> invocations, int total, int matching, Double slowestMillis,
                           String next) {

    public static TapePageView from(UUID hubSession, TapePage page) {
        return new TapePageView(hubSession.toString(), page.sequence(), page.invocations().stream().map(RetainedInvocationView::from).toList(),
            page.total(), page.matching(), millis(page.slowest()), page.next().map(id -> String.valueOf(id.value())).orElse(null));
    }

    public static InvocationId parseCursor(String cursor) {
        try {
            return new InvocationId(Long.parseLong(cursor));
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("A tape cursor is the sequence number a page ended at, as the page's next names it; got '" + cursor + "'",
                failure);
        }
    }
}
