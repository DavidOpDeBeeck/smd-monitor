package app.dodb.smd.monitor.json;

import app.dodb.smd.monitor.hub.ParentSummary;
import app.dodb.smd.monitor.hub.RetainedInvocation;

// The parent is null when the hub found none; a parent the hub found stays known, with its application and message, after it was evicted. A
// handling's parent is the dispatch of its own message.
public record RetainedInvocationView(InvocationView invocation, ParentView parent) {

    static RetainedInvocationView from(RetainedInvocation retained) {
        return new RetainedInvocationView(InvocationView.from(retained.invocation()),
            retained.parent().map(link -> ParentView.from(link, retained.parentRetained())).orElse(null));
    }

    public record ParentView(long sequence, String application, String messageId, String kind, String type, boolean handling, String processingGroup,
                             boolean delivered, boolean retained) {

        static ParentView from(ParentSummary parent, boolean retained) {
            return new ParentView(parent.parent().value(), parent.application().value(), parent.messageId().value().toString(), parent.type().kind().name(),
                parent.type().name(), parent.handling(), parent.processingGroup().orElse(null), parent.delivered(), retained);
        }
    }
}
