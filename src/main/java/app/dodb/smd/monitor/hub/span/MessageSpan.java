package app.dodb.smd.monitor.hub.span;

import app.dodb.smd.api.event.delivery.EventDelivery;
import app.dodb.smd.api.message.MessageId;

import java.time.Instant;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

// A span continues the invocation it links to: a handling the dispatch of its event, a dispatch the handling or the command or query dispatch that
// sent it, or the dispatch another application delivered it from. A dispatch that continues none is an entry point. A processing group's handling
// names the delivery its group received the event by.
public record MessageSpan(SpanRef span,
                          Optional<SpanRef> continues,
                          MessageId messageId,
                          Stage stage,
                          Optional<EventDelivery> delivery,
                          Instant startedAt,
                          String threadName,
                          Outcome outcome) {

    public MessageSpan {
        requireNonNull(span);
        requireNonNull(continues);
        requireNonNull(messageId);
        requireNonNull(stage);
        requireNonNull(delivery);
        requireNonNull(startedAt);
        requireNonNull(threadName);
        requireNonNull(outcome);
        if (delivery.isPresent() != (stage instanceof Stage.Handling)) {
            throw new IllegalArgumentException("SMD span " + span.spanId() + " of " + stage.type().kind() + " " + stage.type().name() + " requires a delivery for "
                + "a processing group's handling and none otherwise; got " + delivery.map(each -> "delivery '" + each.name() + "'").orElse("no delivery"));
        }
    }

    public Instant completedAt() {
        return startedAt.plus(outcome.duration());
    }

    public boolean entryPoint() {
        return stage instanceof Stage.Dispatch && continues.isEmpty();
    }
}
