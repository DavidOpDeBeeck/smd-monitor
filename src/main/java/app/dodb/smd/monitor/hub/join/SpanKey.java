package app.dodb.smd.monitor.hub.join;

import app.dodb.smd.monitor.hub.span.SpanRef;

// A span as the span index keys it: its trace and span IDs as numbers, which take a fraction of the heap of their hexadecimal digits.
public record SpanKey(long traceHigh, long traceLow, long spanId) {

    public static SpanKey of(SpanRef span) {
        return new SpanKey(span.traceHigh(), span.traceLow(), span.spanNumber());
    }
}
