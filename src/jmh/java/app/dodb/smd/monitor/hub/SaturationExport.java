package app.dodb.smd.monitor.hub;

import java.util.List;

// What the exporters of one step of a saturation run over OTLP/HTTP sent in the step's seconds after it settled: the requests, those the hub
// answered busy, the time to its answer, and the size of a request as sent.
record SaturationExport(int rate, long requests, long busy, double answerP50Millis, double answerP99Millis, double answerMaxMillis, double kilobytesP50) {

    static final String HEADER = "| Rate | Requests | Busy | Answer p50, p99, max ms | Request KB p50 |";
    static final String SEPARATOR = "|---".repeat(5) + "|";

    static SaturationExport of(int rate, List<SaturationExporters.Request> requests) {
        return new SaturationExport(rate, requests.size(), requests.stream().filter(SaturationExporters.Request::busy).count(),
            SaturationRun.percentile(requests, SaturationExporters.Request::nanos, 0.5) / 1e6,
            SaturationRun.percentile(requests, SaturationExporters.Request::nanos, 0.99) / 1e6,
            SaturationRun.percentile(requests, SaturationExporters.Request::nanos, 1.0) / 1e6,
            SaturationRun.percentile(requests, SaturationExporters.Request::bytes, 0.5) / 1024);
    }

    String row() {
        return "| %,d | %,d | %,d | %,.1f, %,.1f, %,.1f | %,.0f |".formatted(rate, requests, busy, answerP50Millis, answerP99Millis, answerMaxMillis,
            kilobytesP50);
    }
}
