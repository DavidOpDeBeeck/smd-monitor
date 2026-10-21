package app.dodb.smd.monitor.hub.otlp;

import app.dodb.smd.monitor.hub.MonitorHub;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;

import static java.util.Objects.requireNonNull;

@RestController
class OtlpTraceController {

    static final String PATH = "/v1/traces";
    // Bounds every request as read, and once inflated when compressed: well above the default exporter batch of 512 spans, and read from the
    // request as a stream so a larger one never fills the heap.
    static final int MAX_BODY_BYTES = 16 * 1024 * 1024;
    private static final String PROTOBUF = "application/x-protobuf";
    private static final MediaType PROTOBUF_TYPE = MediaType.parseMediaType(PROTOBUF);
    private final MonitorHub hub;

    OtlpTraceController(MonitorHub hub) {
        this.hub = requireNonNull(hub);
    }

    // Answers once admission decided, before the hub's writer processes the spans. A full queue answers 503 for the whole request; the batches
    // of other applications in it were taken, and the hub counts them once when they arrive again.
    @PostMapping(value = PATH, consumes = PROTOBUF, produces = PROTOBUF)
    ResponseEntity<byte[]> traces(InputStream body, @RequestHeader(name = "Content-Encoding", required = false) String encoding) {
        SpanIntake intake;
        try {
            intake = OtlpTraces.read(read(body, encoding));
        } catch (IllegalArgumentException invalid) {
            return ResponseEntity.badRequest().contentType(PROTOBUF_TYPE).body(OtlpTraces.invalidArgument(invalid.getMessage()));
        }
        var busy = intake.submit(hub::submit);
        if (busy.isPresent()) {
            var retryAfter = busy.get().retryAfter().toSeconds();
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter))
                .contentType(PROTOBUF_TYPE)
                .body(OtlpTraces.unavailable("The intake queue of application '" + busy.get().application() + "' is full; retry after " + retryAfter + " seconds"));
        }
        return ResponseEntity.ok().contentType(PROTOBUF_TYPE).body(OtlpTraces.response(intake.rejected()));
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
