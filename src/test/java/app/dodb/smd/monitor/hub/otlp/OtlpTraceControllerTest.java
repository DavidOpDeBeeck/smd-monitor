package app.dodb.smd.monitor.hub.otlp;

import app.dodb.smd.api.command.Command;
import app.dodb.smd.api.command.CommandMessage;
import app.dodb.smd.api.command.bus.CommandBusContext;
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

class OtlpTraceControllerTest {

    private final MonitorHub hub = new MonitorHub(HubConfig.withDefaults().build());
    private final OtlpTraceController controller = new OtlpTraceController(hub);

    @Test
    void traces_emptyRequest_isAFullSuccess() {
        var response = controller.traces(new ByteArrayInputStream(new byte[0]), null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEmpty();
    }

    @Test
    void traces_requestBeyondTheLimit_answersAnInvalidArgumentNamingTheLimit() {
        var response = controller.traces(new ByteArrayInputStream(new byte[OtlpTraceController.MAX_BODY_BYTES + 1]), "identity");

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(new String(response.getBody(), StandardCharsets.UTF_8)).contains("exceeds " + OtlpTraceController.MAX_BODY_BYTES + " bytes");
    }

    @Test
    void traces_compressedRequestInflatingBeyondTheLimit_answersAnInvalidArgumentNamingTheLimit() {
        var response = controller.traces(new ByteArrayInputStream(gzip(new byte[OtlpTraceController.MAX_BODY_BYTES + 1])), "gzip");

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(new String(response.getBody(), StandardCharsets.UTF_8)).contains("exceeds " + OtlpTraceController.MAX_BODY_BYTES + " bytes once inflated");
    }

    @Test
    void traces_requestThatIsNoGzip_answersAnInvalidArgument() {
        var response = controller.traces(new ByteArrayInputStream(new byte[]{1, 2, 3}), "gzip");

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(new String(response.getBody(), StandardCharsets.UTF_8)).contains("as gzip");
    }

    @Test
    void traces_unsupportedEncoding_answersAnInvalidArgumentNamingIt() {
        var response = controller.traces(new ByteArrayInputStream(new byte[0]), "br");

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(new String(response.getBody(), StandardCharsets.UTF_8)).contains("gzip or none", "'br'");
    }

    @Test
    void traces_applicationQueueFull_answersUnavailableWithRetryAfterForThatApplicationOnly() {
        var controller = new OtlpTraceController(new SynchronousHub(HubConfig.withDefaults().build(), Clock.systemUTC(), Budgets.queuing(1)).hub());
        controller.traces(new ByteArrayInputStream(request("ticket-service")), null);

        var busy = controller.traces(new ByteArrayInputStream(request("ticket-service")), null);
        var other = controller.traces(new ByteArrayInputStream(request("billing-service")), null);

        assertThat(busy.getStatusCode().value()).isEqualTo(503);
        assertThat(busy.getHeaders().getFirst("Retry-After")).isEqualTo("2");
        assertThat(ProtobufMessage.parse(busy.getBody()).string(2)).hasValueSatisfying(reason -> assertThat(reason).contains("'ticket-service'", "full"));
        assertThat(other.getStatusCode().value()).isEqualTo(200);
        assertThat(other.getBody()).isEmpty();
    }

    // An OTLP request holding the dispatch of one command, as the given application's exporter sends it.
    private static byte[] request(String application) {
        var spans = InMemorySpanExporter.create();
        var resource = Resource.create(Attributes.of(SpanReader.SERVICE_NAME, application, SpanReader.HOST_NAME, application + "-1"));
        var provider = SdkTracerProvider.builder().setResource(resource).addSpanProcessor(SimpleSpanProcessor.create(spans)).build();
        var openTelemetry = OpenTelemetrySdk.builder().setTracerProvider(provider).setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance())).build();
        new MessageTracing(openTelemetry).intercept(new CommandBusContext<>(CommandMessage.from(new OpenTicket(), Metadata.withProperties(Map.of()))),
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