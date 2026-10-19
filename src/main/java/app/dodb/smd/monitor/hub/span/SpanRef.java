package app.dodb.smd.monitor.hub.span;

import java.util.HexFormat;

import static java.util.Objects.requireNonNull;

// A span by its trace and span IDs, kept as the numbers their hexadecimal digits spell: the receiving thread parses them once, and the writer keys
// and compares spans by the numbers.
public record SpanRef(long traceHigh, long traceLow, long spanNumber) {

    private static final HexFormat HEX = HexFormat.of();

    public static SpanRef of(String traceId, String spanId) {
        requireNonNull(traceId);
        requireNonNull(spanId);
        if (!lowercaseHex(traceId, 32) || !lowercaseHex(spanId, 16)) {
            throw new IllegalArgumentException("SpanRef requires a trace ID of 32 and a span ID of 16 lowercase hexadecimal digits; got trace ID '" + traceId
                + "', span ID '" + spanId + "'");
        }
        return new SpanRef(Long.parseUnsignedLong(traceId, 0, 16, 16), Long.parseUnsignedLong(traceId, 16, 32, 16), Long.parseUnsignedLong(spanId, 16));
    }

    public String traceId() {
        return HEX.toHexDigits(traceHigh) + HEX.toHexDigits(traceLow);
    }

    public String spanId() {
        return HEX.toHexDigits(spanNumber);
    }

    private static boolean lowercaseHex(String value, int length) {
        if (value.length() != length) {
            return false;
        }
        for (var index = 0; index < length; index++) {
            var digit = value.charAt(index);
            if ((digit < '0' || digit > '9') && (digit < 'a' || digit > 'f')) {
                return false;
            }
        }
        return true;
    }
}
