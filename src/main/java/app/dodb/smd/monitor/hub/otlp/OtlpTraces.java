package app.dodb.smd.monitor.hub.otlp;

import app.dodb.smd.monitor.hub.span.SpanRef;
import app.dodb.smd.opentelemetry.MessageSpans;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

// OTLP/HTTP trace export in binary protobuf, reduced to the spans of SMD's instrumentation. Spans of other instrumentation are
// ignored; SMD spans the hub cannot read or refuses are rejected and counted in a partial success. Spans the hub sheds after it accepted them
// are not: it processes them after it answered.
final class OtlpTraces {

    private static final HexFormat HEX = HexFormat.of();
    private static final int STATUS_CODE_ERROR = 2;
    private static final int STATUS_INVALID_ARGUMENT = 3;
    private static final int STATUS_UNAVAILABLE = 14;

    private OtlpTraces() {
    }

    // Decodes the whole ExportTraceServiceRequest, so the hub receives none of it when it cannot be read.
    static SpanIntake read(byte[] request) {
        var intake = new SpanIntake();
        for (var resourceSpans : ProtobufMessage.parse(request).messages(1)) {
            read(resourceSpans, intake);
        }
        return intake;
    }

    // The ExportTraceServiceResponse: a full success, or a partial success counting the rejected spans.
    static byte[] response(Rejected rejected) {
        return rejected.count() == 0 ? new byte[0] : lengthDelimited(1, codeAndMessage(rejected.count(), rejected.first()));
    }

    // The google.rpc.Status OTLP answers a request it cannot read with.
    static byte[] invalidArgument(String reason) {
        return codeAndMessage(STATUS_INVALID_ARGUMENT, reason);
    }

    // The google.rpc.Status OTLP answers a request to send again later with.
    static byte[] unavailable(String reason) {
        return codeAndMessage(STATUS_UNAVAILABLE, reason);
    }

    private static void read(ProtobufMessage resourceSpans, SpanIntake intake) {
        var resource = attributes(resourceSpans.message(1).map(attributes -> attributes.messages(1)).orElse(List.of()));
        var spans = new ArrayList<ProtobufMessage>();
        for (var scopeSpans : resourceSpans.messages(2)) {
            if (scopeSpans.message(1).flatMap(scope -> scope.string(1)).filter(MessageSpans.INSTRUMENTATION_SCOPE::equals).isPresent()) {
                spans.addAll(scopeSpans.messages(2));
            }
        }
        intake.read(key -> resource.get(key.getKey()), spans, OtlpTraces::span);
    }

    private static ReadSpan span(ProtobufMessage span) {
        var traceId = span.bytes(1);
        var spanId = span.bytes(2);
        if (traceId.length != 16 || spanId.length != 8) {
            throw new IllegalArgumentException("SMD span requires a trace ID of 16 bytes and a span ID of 8 bytes; got " + traceId.length + " and "
                + spanId.length + " bytes");
        }
        var link = span.messages(13).stream().findFirst().map(OtlpTraces::link);
        var attributes = attributes(span.messages(9));
        var failed = span.message(15).map(status -> status.number(3)).orElse(0L) == STATUS_CODE_ERROR;
        return SpanReader.span(SpanRef.of(HEX.formatHex(traceId), HEX.formatHex(spanId)), link, span.number(7), span.number(8), failed,
            key -> attributes.get(key.getKey()));
    }

    private static SpanRef link(ProtobufMessage link) {
        var traceId = link.bytes(1);
        var spanId = link.bytes(2);
        if (traceId.length != 16 || spanId.length != 8) {
            throw new IllegalArgumentException("SMD span link requires a trace ID of 16 bytes and a span ID of 8 bytes; got " + traceId.length + " and "
                + spanId.length + " bytes");
        }
        return SpanRef.of(HEX.formatHex(traceId), HEX.formatHex(spanId));
    }

    // Keeps string values only; SMD's attributes are all strings.
    private static Map<String, String> attributes(List<ProtobufMessage> keyValues) {
        var attributes = new HashMap<String, String>();
        for (var keyValue : keyValues) {
            var key = keyValue.string(1);
            var value = keyValue.message(2).flatMap(any -> any.string(1));
            if (key.isPresent() && value.isPresent()) {
                attributes.put(key.get(), value.get());
            }
        }
        return attributes;
    }

    // Both a partial success and a google.rpc.Status are a number in field 1 and a message in field 2.
    private static byte[] codeAndMessage(long code, String message) {
        var output = new ByteArrayOutputStream();
        varint(output, 1 << 3);
        varint(output, code);
        output.writeBytes(lengthDelimited(2, message.getBytes(StandardCharsets.UTF_8)));
        return output.toByteArray();
    }

    private static byte[] lengthDelimited(int field, byte[] value) {
        var output = new ByteArrayOutputStream();
        varint(output, (long) field << 3 | 2);
        varint(output, value.length);
        output.writeBytes(value);
        return output.toByteArray();
    }

    private static void varint(ByteArrayOutputStream output, long value) {
        var remaining = value;
        while ((remaining & ~0x7FL) != 0) {
            output.write((int) ((remaining & 0x7F) | 0x80));
            remaining >>>= 7;
        }
        output.write((int) remaining);
    }
}
