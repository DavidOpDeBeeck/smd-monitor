package app.dodb.smd.monitor.hub.otlp;

import app.dodb.smd.monitor.hub.span.MessageSpan;
import app.dodb.smd.monitor.hub.span.SinkSend;

import static java.util.Objects.requireNonNull;

// An SMD span as the hub reads it: an invocation, or the sending of an event's publication to one of the publisher's sinks.
sealed interface ReadSpan {

    record Invoked(MessageSpan span) implements ReadSpan {

        public Invoked {
            requireNonNull(span);
        }
    }

    record Sent(SinkSend send) implements ReadSpan {

        public Sent {
            requireNonNull(send);
        }
    }
}
