package app.dodb.smd.monitor.json;

import app.dodb.smd.monitor.hub.MessageDetail;

import java.util.List;
import java.util.UUID;

// The ancestors from the root of the trace down to the parent; the children are the first of how many, and the invocation it most likely
// propagated the failure of is null when there is none.
public record MessageDetailView(String hubSession, long sequence, RetainedInvocationView invocation, long root, List<RetainedInvocationView> ancestors,
                                List<InvocationView> children, int childCount, InvocationView propagatedFrom) {

    public static MessageDetailView from(UUID hubSession, MessageDetail detail) {
        return new MessageDetailView(hubSession.toString(), detail.sequence(), RetainedInvocationView.from(detail.invocation()), detail.root().value(),
            detail.ancestors().stream().map(RetainedInvocationView::from).toList(), detail.children().stream().map(InvocationView::from).toList(),
            detail.childCount(), detail.propagatedFrom().map(InvocationView::from).orElse(null));
    }
}
