package app.dodb.smd.monitor.hub;

import app.dodb.smd.monitor.hub.intake.Refusal;
import app.dodb.smd.monitor.hub.intake.SpanBatch;
import app.dodb.smd.monitor.hub.javalin.SaturationOtlpServer;
import app.dodb.smd.monitor.hub.otlp.OtlpAdmission;
import app.dodb.smd.monitor.hub.otlp.OtlpExports;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Objects.requireNonNull;

// The exporters of a saturation run that exports over OTLP/HTTP, -Dsaturation.transport=otlp or otlp-gzip: the hub's OTLP server as it runs, and
// each batch encoded as its instance's exporter sends it and posted to that server on the loopback address, on a virtual thread of its own, so a
// slow answer holds up no other export, as the hub's exporters do not wait for each other. A request's time runs from sending it to reading the
// answer: the receiving thread reads, inflates, and decodes the request and admits its spans before it answers. A 503 answers busy; any other
// answer than that or a success is a fault of the run, which stops it.
final class SaturationExporters implements AutoCloseable {

    private static final String TRANSPORT_PROPERTY = "saturation.transport";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final Transport transport;
    private final SaturationOtlpServer server;
    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(TIMEOUT).build();
    private final ConcurrentLinkedQueue<Request> requests = new ConcurrentLinkedQueue<>();
    private final AtomicReference<String> fault = new AtomicReference<>();

    private SaturationExporters(Transport transport, SaturationOtlpServer server) {
        this.transport = requireNonNull(transport);
        this.server = requireNonNull(server);
    }

    // How -Dsaturation.transport has the run offer its batches: to the hub directly, by default, or over OTLP/HTTP.
    static Transport configured() {
        var value = System.getProperty(TRANSPORT_PROPERTY, Transport.DIRECT.key());
        return Arrays.stream(Transport.values()).filter(transport -> transport.key().equals(value.strip())).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Saturation run expects -D" + TRANSPORT_PROPERTY + " to be one of "
                + Arrays.stream(Transport.values()).map(Transport::key).toList() + "; got '" + value + "'"));
    }

    static SaturationExporters start(MonitorHub hub, Transport transport) {
        if (transport == Transport.DIRECT) {
            throw new IllegalArgumentException("SaturationExporters export over OTLP/HTTP; got transport " + transport.key());
        }
        return new SaturationExporters(transport, SaturationOtlpServer.start(hub));
    }

    // Each export on a virtual thread of its own.
    ExecutorService executor() {
        return Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("saturation-exporter-", 0).factory());
    }

    // Sends the batch the run offers in the given second, and answers as the hub would have: busy for a 503, otherwise accepted except the spans
    // its partial success rejected.
    Submission export(SpanBatch batch, long second) {
        var encoded = OtlpExports.request(batch);
        var body = transport == Transport.OTLP_GZIP ? OtlpExports.gzip(encoded) : encoded;
        var request = HttpRequest.newBuilder(server.traces()).timeout(TIMEOUT).header("Content-Type", OtlpAdmission.PROTOBUF);
        if (transport == Transport.OTLP_GZIP) {
            request.header("Content-Encoding", "gzip");
        }
        var started = System.nanoTime();
        HttpResponse<byte[]> response;
        try {
            response = client.send(request.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException failure) {
            fault.compareAndSet(null, "export of " + batch.size() + " spans of " + batch.application().value() + " failed: " + failure);
            return busy(batch);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return busy(batch);
        }
        var nanos = System.nanoTime() - started;
        var busy = response.statusCode() == 503;
        requests.add(new Request(second, nanos, body.length, busy));
        if (busy) {
            return busy(batch);
        }
        if (response.statusCode() != 200) {
            fault.compareAndSet(null, "export of " + batch.size() + " spans of " + batch.application().value() + " answered " + response.statusCode());
            return busy(batch);
        }
        var rejected = Math.toIntExact(OtlpExports.rejectedSpans(response.body()));
        return new Submission.Accepted(rejected == 0 ? Optional.empty() : Optional.of(new Refusal(rejected, "rejected by the OTLP server")));
    }

    // The first fault of an export, once there is one.
    Optional<String> fault() {
        return Optional.ofNullable(fault.get());
    }

    Transport transport() {
        return transport;
    }

    // The requests of the given seconds of the run.
    SaturationExport summarize(int rate, long firstSecond, long lastSecond) {
        var step = requests.stream().filter(request -> request.second() >= firstSecond && request.second() <= lastSecond).toList();
        return SaturationExport.of(rate, step);
    }

    @Override
    public void close() {
        client.close();
        server.close();
    }

    private static Submission busy(SpanBatch batch) {
        return new Submission.Busy(batch.application(), MonitorHub.RETRY_AFTER);
    }

    enum Transport {
        DIRECT("direct"),
        OTLP("otlp"),
        OTLP_GZIP("otlp-gzip");

        private final String key;

        Transport(String key) {
            this.key = key;
        }

        String key() {
            return key;
        }
    }

    // One export the run sent: the second it offered it in, how long the hub took to answer, the bytes sent, and whether it answered busy.
    record Request(long second, long nanos, long bytes, boolean busy) {
    }
}
