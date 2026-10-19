package app.dodb.smd.monitor.json;

import app.dodb.smd.monitor.hub.Invocation;
import app.dodb.smd.monitor.hub.span.Outcome;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;

import java.util.Optional;

import static app.dodb.smd.monitor.json.FrameView.epochMillis;
import static app.dodb.smd.monitor.json.FrameView.millis;

// The processing group is null for a dispatch and for the handling of a command or query. An invocation has a parent when its span links to
// the invocation it continues, which the hub may not have found or may no longer retain; a dispatch without one is an entry point.
public record InvocationView(long sequence, String application, String messageId, boolean hasParent, String kind, String type, boolean handling,
                             String processingGroup, String instance, double startedAtMillis, String threadName, OutcomeView outcome) {

    static InvocationView from(Invocation invocation) {
        var span = invocation.span();
        return new InvocationView(invocation.id().value(), invocation.application().value(), span.messageId().value().toString(),
            span.continues().isPresent(), invocation.type().kind().name(), invocation.type().name(), invocation.handling(),
            invocation.processingGroup().orElse(null), invocation.instance().value(), epochMillis(span.startedAt()), span.threadName(),
            OutcomeView.from(span.outcome()));
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "status")
    @JsonSubTypes({@JsonSubTypes.Type(OutcomeView.Completed.class), @JsonSubTypes.Type(OutcomeView.Failed.class)})
    public sealed interface OutcomeView {

        static OutcomeView from(Outcome outcome) {
            return switch (outcome) {
                case Outcome.Completed(var duration) -> new Completed(millis(Optional.of(duration)));
                case Outcome.Failed(var duration, var failureType) -> new Failed(millis(Optional.of(duration)), failureType);
            };
        }

        @JsonTypeName("COMPLETED")
        record Completed(double durationMillis) implements OutcomeView {
        }

        @JsonTypeName("FAILED")
        record Failed(double durationMillis, String failureType) implements OutcomeView {
        }
    }
}
