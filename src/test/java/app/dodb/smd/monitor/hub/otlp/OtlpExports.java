package app.dodb.smd.monitor.hub.otlp;

import app.dodb.smd.monitor.hub.intake.SpanBatch;
import app.dodb.smd.monitor.hub.span.MessageKind;
import app.dodb.smd.monitor.hub.span.MessageSpan;
import app.dodb.smd.monitor.hub.span.Outcome;
import app.dodb.smd.monitor.hub.span.SinkSend;
import app.dodb.smd.monitor.hub.span.SpanRef;
import app.dodb.smd.monitor.hub.span.Stage;
import app.dodb.smd.opentelemetry.MessageSpans;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.exporter.internal.otlp.traces.TraceRequestMarshaler;
import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.testing.trace.TestSpanData;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static app.dodb.smd.opentelemetry.MessageSpans.COMMAND;
import static app.dodb.smd.opentelemetry.MessageSpans.DELIVERY;
import static app.dodb.smd.opentelemetry.MessageSpans.DESTINATION_NAME;
import static app.dodb.smd.opentelemetry.MessageSpans.ERROR_TYPE;
import static app.dodb.smd.opentelemetry.MessageSpans.EVENT;
import static app.dodb.smd.opentelemetry.MessageSpans.MESSAGE_ID;
import static app.dodb.smd.opentelemetry.MessageSpans.MESSAGE_KIND;
import static app.dodb.smd.opentelemetry.MessageSpans.MESSAGING_SYSTEM;
import static app.dodb.smd.opentelemetry.MessageSpans.OPERATION_NAME;
import static app.dodb.smd.opentelemetry.MessageSpans.OPERATION_TYPE;
import static app.dodb.smd.opentelemetry.MessageSpans.PROCESS;
import static app.dodb.smd.opentelemetry.MessageSpans.PROCESSING_GROUP;
import static app.dodb.smd.opentelemetry.MessageSpans.PUBLISH;
import static app.dodb.smd.opentelemetry.MessageSpans.QUERY;
import static app.dodb.smd.opentelemetry.MessageSpans.SEND;
import static app.dodb.smd.opentelemetry.MessageSpans.SYSTEM;
import static app.dodb.smd.opentelemetry.MessageSpans.THREAD_NAME;
import static io.opentelemetry.api.common.AttributeKey.stringKey;

// A batch as the instance that exported it sends it over OTLP/HTTP: one resource naming the application, its host, and its session, and SMD's
// spans as its instrumentation records them, written by the OTLP exporter's own encoder. The spans carry what SMD sets beyond what the hub reads,
// a name, a kind, a parent, and the messaging system, so decoding them costs what decoding an exporter's request does.
public final class OtlpExports {

    private static final InstrumentationScopeInfo SCOPE = InstrumentationScopeInfo.create(MessageSpans.INSTRUMENTATION_SCOPE);
    private static final Attributes SDK = Attributes.of(stringKey("telemetry.sdk.language"), "java", stringKey("telemetry.sdk.name"), "opentelemetry",
        stringKey("telemetry.sdk.version"), "1.55.0");

    private OtlpExports() {
    }

    public static byte[] request(SpanBatch batch) {
        var resource = Resource.create(SDK.toBuilder()
            .put(SpanReader.SERVICE_NAME, batch.application().value())
            .put(SpanReader.HOST_NAME, batch.instance().value())
            .put(SpanReader.SERVICE_INSTANCE_ID, batch.session().value())
            .build());
        var spans = new ArrayList<SpanData>(batch.size());
        batch.spans().forEach(span -> spans.add(invoked(span, resource)));
        batch.sends().forEach(send -> spans.add(sent(send, resource)));
        var output = new ByteArrayOutputStream();
        try {
            TraceRequestMarshaler.create(spans).writeBinaryTo(output);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        return output.toByteArray();
    }

    public static byte[] gzip(byte[] request) {
        var output = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(output)) {
            gzip.write(request);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        return output.toByteArray();
    }

    // The spans the hub rejected, from the partial success of an ExportTraceServiceResponse: its field 1, whose field 1 counts them. A full
    // success is empty.
    public static long rejectedSpans(byte[] response) {
        var partialSuccess = ProtobufMessage.parse(response).message(1);
        return partialSuccess.map(message -> message.number(1)).orElse(0L);
    }

    private static SpanData invoked(MessageSpan span, Resource resource) {
        var type = span.stage().type();
        var operation = switch (span.stage()) {
            case Stage.Dispatch ignored -> type.kind() == MessageKind.EVENT ? PUBLISH : SEND;
            case Stage.Handling ignored -> PROCESS;
        };
        var attributes = smd(span.stage() instanceof Stage.Handling ? PROCESS : SEND, operation, kind(type.kind()), type.name())
            .put(MESSAGE_ID, span.messageId().value().toString())
            .put(THREAD_NAME, span.threadName());
        if (span.stage() instanceof Stage.Handling(var ignored, var processingGroup)) {
            attributes.put(PROCESSING_GROUP, processingGroup);
        }
        span.delivery().ifPresent(delivery -> attributes.put(DELIVERY, delivery.name()));
        if (span.outcome() instanceof Outcome.Failed failed) {
            attributes.put(ERROR_TYPE, failed.failureType());
        }
        var status = span.outcome() instanceof Outcome.Failed ? StatusData.error() : StatusData.unset();
        var kind = operation.equals(PROCESS) ? SpanKind.CONSUMER : SpanKind.PRODUCER;
        var links = span.continues().map(continued -> List.of(LinkData.create(context(continued)))).orElse(List.of());
        var parent = span.continues().map(OtlpExports::context).orElse(SpanContext.getInvalid());
        return data(span.span(), parent, operation + " " + type.name(), kind, span.startedAt(), span.completedAt(), attributes.build(), links, status, resource);
    }

    private static SpanData sent(SinkSend send, Resource resource) {
        var attributes = smd(SEND, SEND, EVENT, send.event().name()).put(DELIVERY, send.delivery().name()).build();
        return data(send.span(), SpanContext.getInvalid(), SEND + " " + send.event().name(), SpanKind.PRODUCER, send.startedAt(), send.startedAt(), attributes,
            List.of(), StatusData.unset(), resource);
    }

    private static AttributesBuilder smd(String operationType, String operationName, String kind, String destination) {
        return Attributes.builder()
            .put(MESSAGING_SYSTEM, SYSTEM)
            .put(OPERATION_TYPE, operationType)
            .put(OPERATION_NAME, operationName)
            .put(MESSAGE_KIND, kind)
            .put(DESTINATION_NAME, destination);
    }

    private static SpanData data(SpanRef span, SpanContext parent, String name, SpanKind kind, Instant start, Instant end, Attributes attributes,
                                 List<LinkData> links, StatusData status, Resource resource) {
        return TestSpanData.builder()
            .setSpanContext(context(span))
            .setParentSpanContext(parent)
            .setName(name)
            .setKind(kind)
            .setStartEpochNanos(nanos(start))
            .setEndEpochNanos(nanos(end))
            .setHasEnded(true)
            .setAttributes(attributes)
            .setTotalAttributeCount(attributes.size())
            .setLinks(links)
            .setTotalRecordedLinks(links.size())
            .setStatus(status)
            .setResource(resource)
            .setInstrumentationScopeInfo(SCOPE)
            .build();
    }

    private static SpanContext context(SpanRef span) {
        return SpanContext.create(span.traceId(), span.spanId(), TraceFlags.getSampled(), TraceState.getDefault());
    }

    private static String kind(MessageKind kind) {
        return switch (kind) {
            case COMMAND -> COMMAND;
            case QUERY -> QUERY;
            case EVENT -> EVENT;
        };
    }

    private static long nanos(Instant instant) {
        return instant.getEpochSecond() * 1_000_000_000L + instant.getNano();
    }
}
