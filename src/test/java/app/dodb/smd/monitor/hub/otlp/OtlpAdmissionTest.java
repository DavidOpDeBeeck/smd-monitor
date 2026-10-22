package app.dodb.smd.monitor.hub.otlp;

import app.dodb.smd.api.command.Command;
import app.dodb.smd.api.command.CommandMessage;
import app.dodb.smd.api.command.bus.CommandBusInterceptorChain;
import app.dodb.smd.api.metadata.Metadata;
import app.dodb.smd.monitor.hub.HubConfig;
import app.dodb.smd.monitor.hub.MonitorHub;
import app.dodb.smd.monitor.hub.SynchronousHub;
import app.dodb.smd.monitor.hub.budget.Budgets;
import app.dodb.smd.opentelemetry.MessageTracing;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.exporter.internal.otlp.traces.TraceRequestMarshaler;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class OtlpAdmissionTest {

    private final MonitorHub hub = new MonitorHub(HubConfig.withDefaults().build());
    private final OtlpAdmission admission = new OtlpAdmission(hub);

    @Test
    void admit_emptyRequest_isAFullSuccess() {
        var response = admission.admit(new ByteArrayInputStream(new byte[0]), null);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).isEmpty();
    }

    @Test
    void admit_compressedRequest_admitsItsSpans() {
        var response = admission.admit(new ByteArrayInputStream(gzip(request("ticket-service"))), "gzip");

        assertThat(response).isInstanceOf(OtlpAnswer.Accepted.class);
        assertThat(response.body()).isEmpty();
    }

    @Test
    void admit_requestBeyondTheLimit_answersAnInvalidArgumentNamingTheLimit() {
        var response = admission.admit(new ByteArrayInputStream(new byte[OtlpAdmission.MAX_BODY_BYTES + 1]), "identity");

        assertThat(response.status()).isEqualTo(400);
        assertThat(new String(response.body(), StandardCharsets.UTF_8)).contains("exceeds " + OtlpAdmission.MAX_BODY_BYTES + " bytes");
    }

    @Test
    void admit_compressedRequestInflatingBeyondTheLimit_answersAnInvalidArgumentNamingTheLimit() {
        var response = admission.admit(new ByteArrayInputStream(gzip(new byte[OtlpAdmission.MAX_BODY_BYTES + 1])), "gzip");

        assertThat(response.status()).isEqualTo(400);
        assertThat(new String(response.body(), StandardCharsets.UTF_8)).contains("exceeds " + OtlpAdmission.MAX_BODY_BYTES + " bytes once inflated");
    }

    @Test
    void admit_requestThatIsNoGzip_answersAnInvalidArgument() {
        var response = admission.admit(new ByteArrayInputStream(new byte[]{1, 2, 3}), "gzip");

        assertThat(response.status()).isEqualTo(400);
        assertThat(new String(response.body(), StandardCharsets.UTF_8)).contains("as gzip");
    }

    @Test
    void admit_unsupportedEncoding_answersAnInvalidArgumentNamingIt() {
        var response = admission.admit(new ByteArrayInputStream(new byte[0]), "br");

        assertThat(response.status()).isEqualTo(400);
        assertThat(new String(response.body(), StandardCharsets.UTF_8)).contains("gzip or none", "'br'");
    }

    @Test
    void admit_applicationQueueFull_answersUnavailableWithRetryAfterForThatApplicationOnly() {
        var admission = new OtlpAdmission(new SynchronousHub(HubConfig.withDefaults().build(), Clock.systemUTC(), Budgets.queuing(1)).hub());
        admission.admit(new ByteArrayInputStream(request("ticket-service")), null);

        var busy = admission.admit(new ByteArrayInputStream(request("ticket-service")), null);
        var other = admission.admit(new ByteArrayInputStream(request("billing-service")), null);

        assertThat(busy.status()).isEqualTo(503);
        assertThat(busy).isInstanceOfSatisfying(OtlpAnswer.Busy.class, answer -> assertThat(answer.retryAfterSeconds()).isEqualTo(2));
        assertThat(ProtobufMessage.parse(busy.body()).string(2)).hasValueSatisfying(reason -> assertThat(reason).contains("'ticket-service'", "full"));
        assertThat(other.status()).isEqualTo(200);
        assertThat(other.body()).isEmpty();
    }

    // An OTLP request holding the dispatch of one command, as the given application's exporter sends it.
    private static byte[] request(String application) {
        var spans = InMemorySpanExporter.create();
        var resource = Resource.create(Attributes.of(SpanReader.SERVICE_NAME, application, SpanReader.HOST_NAME, application + "-1"));
        var provider = SdkTracerProvider.builder().setResource(resource).addSpanProcessor(SimpleSpanProcessor.create(spans)).build();
        var openTelemetry = OpenTelemetrySdk.builder().setTracerProvider(provider).setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance())).build();
        new MessageTracing(openTelemetry).intercept(CommandMessage.from(new OpenTicket(), Metadata.withProperties(Map.of())),
            CommandBusInterceptorChain.create(ignored -> null, List.of()));
        var output = new ByteArrayOutputStream();
        try {
            TraceRequestMarshaler.create(spans.getFinishedSpanItems()).writeBinaryTo(output);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        return output.toByteArray();
    }

    private static byte[] gzip(byte[] bytes) {
        var output = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(output)) {
            gzip.write(bytes);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        return output.toByteArray();
    }

    private record OpenTicket() implements Command<Object> {
    }
}
