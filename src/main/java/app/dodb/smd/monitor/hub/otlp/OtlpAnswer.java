package app.dodb.smd.monitor.hub.otlp;

import static java.util.Objects.requireNonNull;

// What the endpoint answers an export, its body an ExportTraceServiceResponse or a google.rpc.Status in binary protobuf.
public sealed interface OtlpAnswer {

    int status();

    byte[] body();

    // Admitted, apart from the spans the body counts as rejected.
    record Accepted(byte[] body) implements OtlpAnswer {

        public Accepted {
            requireNonNull(body);
        }

        @Override
        public int status() {
            return 200;
        }
    }

    // Unreadable, so none of it was admitted.
    record Invalid(byte[] body) implements OtlpAnswer {

        public Invalid {
            requireNonNull(body);
        }

        @Override
        public int status() {
            return 400;
        }
    }

    // An application's intake queue is full; the exporter retries the whole export after the given seconds.
    record Busy(long retryAfterSeconds, byte[] body) implements OtlpAnswer {

        public Busy {
            requireNonNull(body);
        }

        @Override
        public int status() {
            return 503;
        }
    }
}
