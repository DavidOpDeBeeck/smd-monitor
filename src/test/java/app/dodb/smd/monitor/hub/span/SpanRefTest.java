package app.dodb.smd.monitor.hub.span;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpanRefTest {

    private static final String TRACE_ID = "0123456789abcdef0123456789abcdef";
    private static final String SPAN_ID = "0123456789abcdef";

    @Test
    void create_lowercaseHexadecimalIds_keepsThem() {
        var ref = SpanRef.of(TRACE_ID, SPAN_ID);

        assertThat(ref.traceId()).isEqualTo(TRACE_ID);
        assertThat(ref.spanId()).isEqualTo(SPAN_ID);
    }

    @Test
    void create_idsBeyondTheSignedRange_keepsTheirDigitsAndEquality() {
        var traceId = "ffffffffffffffff8000000000000000";
        var spanId = "8000000000000001";

        var ref = SpanRef.of(traceId, spanId);

        assertThat(ref.traceId()).isEqualTo(traceId);
        assertThat(ref.spanId()).isEqualTo(spanId);
        assertThat(ref).isEqualTo(SpanRef.of(traceId, spanId)).isNotEqualTo(SpanRef.of(traceId, "8000000000000002"));
    }

    @Test
    void create_uppercaseTraceId_throwsNamingBothIds() {
        assertThatThrownBy(() -> SpanRef.of(TRACE_ID.toUpperCase(), SPAN_ID))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("32 and a span ID of 16 lowercase hexadecimal digits")
            .hasMessageContaining("'" + TRACE_ID.toUpperCase() + "'")
            .hasMessageContaining("'" + SPAN_ID + "'");
    }

    @Test
    void create_shortTraceId_throws() {
        assertThatThrownBy(() -> SpanRef.of(TRACE_ID.substring(1), SPAN_ID))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void create_spanIdWithNonHexadecimalDigit_throws() {
        assertThatThrownBy(() -> SpanRef.of(TRACE_ID, "0123456789abcdeg"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void create_longSpanId_throws() {
        assertThatThrownBy(() -> SpanRef.of(TRACE_ID, SPAN_ID + "0"))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
