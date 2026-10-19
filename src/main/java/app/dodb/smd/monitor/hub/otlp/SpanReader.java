package app.dodb.smd.monitor.hub.otlp;

import app.dodb.smd.api.event.delivery.EventDelivery;
import app.dodb.smd.api.message.MessageId;
import app.dodb.smd.monitor.hub.intake.SpanBatch;
import app.dodb.smd.monitor.hub.span.ApplicationName;
import app.dodb.smd.monitor.hub.span.InstanceName;
import app.dodb.smd.monitor.hub.span.MessageKind;
import app.dodb.smd.monitor.hub.span.MessageSpan;
import app.dodb.smd.monitor.hub.span.MessageType;
import app.dodb.smd.monitor.hub.span.Outcome;
import app.dodb.smd.monitor.hub.span.SessionId;
import app.dodb.smd.monitor.hub.span.SinkSend;
import app.dodb.smd.monitor.hub.span.SpanRef;
import app.dodb.smd.monitor.hub.span.Stage;
import io.opentelemetry.api.common.AttributeKey;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import static app.dodb.smd.opentelemetry.MessageSpans.COMMAND;
import static app.dodb.smd.opentelemetry.MessageSpans.DELIVERY;
import static app.dodb.smd.opentelemetry.MessageSpans.DESTINATION_NAME;
import static app.dodb.smd.opentelemetry.MessageSpans.ERROR_TYPE;
import static app.dodb.smd.opentelemetry.MessageSpans.EVENT;
import static app.dodb.smd.opentelemetry.MessageSpans.MESSAGE_ID;
import static app.dodb.smd.opentelemetry.MessageSpans.MESSAGE_KIND;
import static app.dodb.smd.opentelemetry.MessageSpans.OPERATION_NAME;
import static app.dodb.smd.opentelemetry.MessageSpans.OPERATION_TYPE;
import static app.dodb.smd.opentelemetry.MessageSpans.PROCESS;
import static app.dodb.smd.opentelemetry.MessageSpans.PROCESSING_GROUP;
import static app.dodb.smd.opentelemetry.MessageSpans.PUBLISH;
import static app.dodb.smd.opentelemetry.MessageSpans.QUERY;
import static app.dodb.smd.opentelemetry.MessageSpans.SEND;
import static app.dodb.smd.opentelemetry.MessageSpans.THREAD_NAME;
import static io.opentelemetry.api.common.AttributeKey.stringKey;

// Reads the spans of SMD's instrumentation from the OpenTelemetry data model, however they arrived.
final class SpanReader {

    static final AttributeKey<String> SERVICE_NAME = stringKey("service.name");
    static final AttributeKey<String> SERVICE_INSTANCE_ID = stringKey("service.instance.id");
    static final AttributeKey<String> HOST_NAME = stringKey("host.name");
    static final InstanceName UNNAMED_INSTANCE = new InstanceName("unnamed");
    // The failure type of a span that something other than SMD's instrumentation failed, which records no error type.
    static final String UNKNOWN_FAILURE_TYPE = "unknown";

    private SpanReader() {
    }

    // The application is the service; its instances are told apart by host name, and their sessions by service instance ID. A resource
    // naming neither reports as one unnamed instance of its application.
    static SpanBatch report(Function<AttributeKey<String>, String> resource, List<ReadSpan> read) {
        var service = resource.apply(SERVICE_NAME);
        if (service == null) {
            throw new IllegalArgumentException("SMD spans require the resource attribute " + SERVICE_NAME + ", the application the hub groups instances by");
        }
        var instanceId = resource.apply(SERVICE_INSTANCE_ID);
        var host = resource.apply(HOST_NAME);
        var application = valid(SERVICE_NAME, service, ApplicationName::new);
        var instance = host != null ? valid(HOST_NAME, host, InstanceName::new)
            : instanceId != null ? valid(SERVICE_INSTANCE_ID, instanceId, InstanceName::new) : UNNAMED_INSTANCE;
        var session = instanceId != null ? valid(SERVICE_INSTANCE_ID, instanceId, SessionId::new) : new SessionId(instance.value());
        var spans = new ArrayList<MessageSpan>();
        var sends = new ArrayList<SinkSend>();
        for (var span : read) {
            switch (span) {
                case ReadSpan.Invoked(var invocation) -> spans.add(invocation);
                case ReadSpan.Sent(var send) -> sends.add(send);
            }
        }
        return new SpanBatch(application, instance, session, spans, sends);
    }

    private static <T> T valid(AttributeKey<String> attribute, String value, Function<String, T> parse) {
        try {
            return parse.apply(value);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("SMD spans require a valid resource attribute " + attribute + "; got '" + value + "': " + invalid.getMessage(), invalid);
        }
    }

    // An SMD span links to the SMD invocation it continues, whatever span its parent is: lineage follows the link alone. An event's span sending it to
    // a sink is no invocation: whatever reads the event from the sink continues its publication.
    static ReadSpan span(SpanRef span, Optional<SpanRef> link, long startEpochNanos, long endEpochNanos, boolean failed,
                         Function<AttributeKey<String>, String> attributes) {
        var attribute = new Attributes(span, attributes);
        var type = new MessageType(kind(span, attribute.required(MESSAGE_KIND)), attribute.required(DESTINATION_NAME));
        if (attribute.required(OPERATION_TYPE).equals(SEND) && type.kind() == MessageKind.EVENT && sendsToASink(span, type, attribute.required(OPERATION_NAME))) {
            return new ReadSpan.Sent(sending(span, type, attribute, Instant.ofEpochSecond(0, startEpochNanos)));
        }
        var stage = switch (attribute.required(OPERATION_TYPE)) {
            case SEND -> dispatch(span, type, attribute.optional(PROCESSING_GROUP));
            case PROCESS -> handling(span, type, attribute.optional(PROCESSING_GROUP));
            default -> throw new IllegalArgumentException("SMD span " + span.spanId() + " requires " + OPERATION_TYPE + " '" + SEND + "' or '" + PROCESS
                + "'; got '" + attribute.required(OPERATION_TYPE) + "'");
        };
        var delivery = delivery(span, stage, attribute.optional(DELIVERY));
        if (endEpochNanos < startEpochNanos) {
            throw new IllegalArgumentException("SMD span " + span.spanId() + " requires an end time after its start; got start " + startEpochNanos + " ns, end "
                + endEpochNanos + " ns");
        }
        var duration = Duration.ofNanos(endEpochNanos - startEpochNanos);
        var outcome = failed ? new Outcome.Failed(duration, attribute.optional(ERROR_TYPE).orElse(UNKNOWN_FAILURE_TYPE)) : new Outcome.Completed(duration);
        return new ReadSpan.Invoked(new MessageSpan(span, link, messageId(span, attribute.required(MESSAGE_ID)), stage, delivery,
            Instant.ofEpochSecond(0, startEpochNanos), attribute.required(THREAD_NAME), outcome));
    }

    // An event is published once and then sent to each of the publisher's sinks.
    private static boolean sendsToASink(SpanRef span, MessageType type, String operationName) {
        return switch (operationName) {
            case PUBLISH -> false;
            case SEND -> true;
            default -> throw new IllegalArgumentException("SMD span " + span.spanId() + " sends event " + type.name() + " and requires " + OPERATION_NAME + " '"
                + PUBLISH + "' or '" + SEND + "'; got '" + operationName + "'");
        };
    }

    // Sending an event to a sink names the sink's delivery and no processing group.
    private static SinkSend sending(SpanRef span, MessageType type, Attributes attribute, Instant startedAt) {
        var processingGroup = attribute.optional(PROCESSING_GROUP);
        if (processingGroup.isPresent()) {
            throw new IllegalArgumentException("SMD span " + span.spanId() + " sends event " + type.name() + " to a sink and requires no " + PROCESSING_GROUP
                + "; got '" + processingGroup.get() + "'");
        }
        var delivery = attribute.optional(DELIVERY).orElseThrow(() -> new IllegalArgumentException("SMD span " + span.spanId() + " sends event " + type.name()
            + " to a sink and requires " + DELIVERY + ", the delivery of the sink"));
        try {
            return new SinkSend(span, type, new EventDelivery(delivery), startedAt);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("SMD span " + span.spanId() + " requires a non-blank " + DELIVERY + "; got '" + delivery + "'", invalid);
        }
    }

    private static Stage.Dispatch dispatch(SpanRef span, MessageType type, Optional<String> processingGroup) {
        if (processingGroup.isPresent()) {
            throw new IllegalArgumentException("SMD span " + span.spanId() + " sends " + type.kind() + " " + type.name() + " and requires no "
                + PROCESSING_GROUP + "; got '" + processingGroup.get() + "'");
        }
        return new Stage.Dispatch(type);
    }

    // Each processing group handles an event. Since SMD 0.0.12 a command's or query's send span covers its handler, and it has no process span.
    private static Stage.Handling handling(SpanRef span, MessageType type, Optional<String> processingGroup) {
        if (type.kind() != MessageKind.EVENT) {
            throw new IllegalArgumentException("SMD span " + span.spanId() + " handles " + type.kind() + " " + type.name() + ", but only an event has a '"
                + PROCESS + "' span: a command's or query's '" + SEND + "' span covers its handler since SMD 0.0.12");
        }
        if (processingGroup.isEmpty()) {
            throw new IllegalArgumentException("SMD span " + span.spanId() + " handles event " + type.name() + " and requires " + PROCESSING_GROUP
                + ", the processing group handling it");
        }
        return new Stage.Handling(type, processingGroup.get());
    }

    // A processing group's handling names the delivery its group received the event by; no other span has one.
    private static Optional<EventDelivery> delivery(SpanRef span, Stage stage, Optional<String> delivery) {
        var type = stage.type();
        if (!(stage instanceof Stage.Handling(var ignored, var processingGroup))) {
            if (delivery.isPresent()) {
                throw new IllegalArgumentException("SMD span " + span.spanId() + " of " + type.kind() + " " + type.name() + " is no processing group's handling "
                    + "and requires no " + DELIVERY + "; got '" + delivery.get() + "'");
            }
            return Optional.empty();
        }
        if (delivery.isEmpty()) {
            throw new IllegalArgumentException("SMD span " + span.spanId() + " handles event " + type.name() + " in processing group '" + processingGroup
                + "' and requires " + DELIVERY + ", the delivery the group received the event by");
        }
        try {
            return Optional.of(new EventDelivery(delivery.get()));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("SMD span " + span.spanId() + " requires a non-blank " + DELIVERY + "; got '" + delivery.get() + "'", invalid);
        }
    }

    private static MessageKind kind(SpanRef span, String kind) {
        return switch (kind) {
            case COMMAND -> MessageKind.COMMAND;
            case QUERY -> MessageKind.QUERY;
            case EVENT -> MessageKind.EVENT;
            default -> throw new IllegalArgumentException("SMD span " + span.spanId() + " requires a message kind '" + COMMAND + "', '" + QUERY + "', or '"
                + EVENT + "'; got '" + kind + "'");
        };
    }

    private static MessageId messageId(SpanRef span, String value) {
        try {
            return new MessageId(UUID.fromString(value));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("SMD span " + span.spanId() + " requires " + MESSAGE_ID + " to be a UUID; got '" + value + "'", invalid);
        }
    }

    private record Attributes(SpanRef span, Function<AttributeKey<String>, String> values) {

        String required(AttributeKey<String> key) {
            return optional(key).orElseThrow(() -> new IllegalArgumentException("SMD span " + span.spanId() + " requires the attribute " + key));
        }

        Optional<String> optional(AttributeKey<String> key) {
            return Optional.ofNullable(values.apply(key));
        }
    }
}
