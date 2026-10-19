package app.dodb.smd.monitor.json;

import app.dodb.smd.monitor.hub.InvocationId;
import app.dodb.smd.monitor.hub.Trace;
import app.dodb.smd.monitor.hub.TraceCursor;
import app.dodb.smd.monitor.hub.TracePage;
import app.dodb.smd.monitor.hub.span.ApplicationName;
import app.dodb.smd.monitor.hub.span.MessageKind;

import java.util.List;
import java.util.UUID;

import static app.dodb.smd.monitor.json.FrameView.epochMillis;
import static app.dodb.smd.monitor.json.FrameView.millis;
import static java.util.Optional.of;

// The next page follows the cursor, which is null on the last page.
public record TracePageView(String hubSession, long sequence, List<TraceView> traces, int total, int matching, String next) {

    public static TracePageView from(UUID hubSession, TracePage page) {
        return new TracePageView(hubSession.toString(), page.sequence(), page.traces().stream().map(TraceView::from).toList(), page.total(), page.matching(),
            page.next().map(cursor -> cursor.value() + ":" + cursor.root().value()).orElse(null));
    }

    public static TraceCursor parseCursor(String cursor) {
        var separator = cursor.indexOf(':');
        try {
            return new TraceCursor(Long.parseLong(cursor.substring(0, separator)), new InvocationId(Long.parseLong(cursor.substring(separator + 1))));
        } catch (IllegalArgumentException | IndexOutOfBoundsException failure) {
            throw new IllegalArgumentException("A trace cursor is the value and the root a page ended at, as the page's next names it; got '" + cursor + "'",
                failure);
        }
    }

    // The composition counts the dispatches of each kind besides the root, and the handlings.
    public record TraceView(InvocationView root, double startedAtMillis, double durationMillis, int messages, int handlings, int failures, int threads,
                            List<String> applications, CompositionView composition) {

        static TraceView from(Trace trace) {
            var composition = trace.composition();
            return new TraceView(InvocationView.from(trace.root()), epochMillis(trace.start()), millis(of(trace.duration())), trace.messages(),
                trace.handlings(), trace.failures(), trace.threads(), trace.applications().stream().map(ApplicationName::value).toList(),
                new CompositionView(composition.get(MessageKind.COMMAND), composition.get(MessageKind.QUERY), composition.get(MessageKind.EVENT),
                    trace.handlings()));
        }
    }

    public record CompositionView(int commands, int queries, int events, int handlings) {
    }
}
