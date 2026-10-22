package app.dodb.smd.monitor.hub.otlp;

import app.dodb.smd.monitor.hub.MonitorHub;

import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;

import static java.util.Objects.requireNonNull;

// The OTLP/HTTP endpoint's requests, which the receiving thread decodes and admits.
public final class OtlpAdmission {

    public static final String PATH = "/v1/traces";
    public static final String PROTOBUF = "application/x-protobuf";
    // Bounds every request as read, and once inflated when compressed: well above the default exporter batch of 512 spans, and read from the
    // request as a stream so a larger one never fills the heap.
    static final int MAX_BODY_BYTES = 16 * 1024 * 1024;
    private final MonitorHub hub;

    public OtlpAdmission(MonitorHub hub) {
        this.hub = requireNonNull(hub);
    }

    // Answers once admission decided, before the hub's writer processes the spans. A full queue answers 503 for the whole request; the batches
    // of other applications in it were taken, and the hub counts them once when they arrive again. The body is as sent, gzip or not; the content
    // encoding is null when the request names none.
    public OtlpAnswer admit(InputStream body, String contentEncoding) {
        SpanIntake intake;
        try {
            intake = OtlpTraces.read(read(body, contentEncoding));
        } catch (IllegalArgumentException invalid) {
            return new OtlpAnswer.Invalid(OtlpTraces.invalidArgument(invalid.getMessage()));
        }
        var busy = intake.submit(hub::submit);
        if (busy.isPresent()) {
            var retryAfter = busy.get().retryAfter().toSeconds();
            return new OtlpAnswer.Busy(retryAfter,
                OtlpTraces.unavailable("The intake queue of application '" + busy.get().application() + "' is full; retry after " + retryAfter + " seconds"));
        }
        return new OtlpAnswer.Accepted(OtlpTraces.response(intake.rejected()));
    }

    private static byte[] read(InputStream body, String encoding) {
        var gzip = gzip(encoding);
        try (var input = gzip ? new GZIPInputStream(body) : body) {
            var bytes = input.readNBytes(MAX_BODY_BYTES + 1);
            if (bytes.length > MAX_BODY_BYTES) {
                throw new IllegalArgumentException("OTLP request exceeds " + MAX_BODY_BYTES + " bytes" + (gzip ? " once inflated" : ""));
            }
            return bytes;
        } catch (IOException failure) {
            throw new IllegalArgumentException("OTLP request cannot be read" + (gzip ? " as gzip" : "") + ": " + failure.getMessage(), failure);
        }
    }

    private static boolean gzip(String encoding) {
        if (encoding == null || encoding.equalsIgnoreCase("identity")) {
            return false;
        }
        if (!encoding.equalsIgnoreCase("gzip")) {
            throw new IllegalArgumentException("OTLP request requires Content-Encoding gzip or none; got '" + encoding + "'");
        }
        return true;
    }
}
