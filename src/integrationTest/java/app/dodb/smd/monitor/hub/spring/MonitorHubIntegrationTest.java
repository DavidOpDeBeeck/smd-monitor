package app.dodb.smd.monitor.hub.spring;

import app.dodb.smd.api.command.Command;
import app.dodb.smd.api.command.CommandGateway;
import app.dodb.smd.api.command.CommandHandler;
import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventHandler;
import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.api.event.EventPublisher;
import app.dodb.smd.api.event.ProcessingGroup;
import app.dodb.smd.api.metadata.Metadata;
import app.dodb.smd.monitor.hub.Invocation;
import app.dodb.smd.monitor.hub.InvocationFilter;
import app.dodb.smd.monitor.hub.MonitorHub;
import app.dodb.smd.monitor.hub.ParentSummary;
import app.dodb.smd.monitor.hub.RetainedInvocation;
import app.dodb.smd.monitor.hub.TapeQuery;
import app.dodb.smd.monitor.hub.span.ApplicationName;
import app.dodb.smd.monitor.hub.span.Stage;
import app.dodb.smd.monitor.json.FrameDelta;
import app.dodb.smd.monitor.json.Protocol;
import app.dodb.smd.spring.EnableSMD;
import jakarta.websocket.ClientEndpointConfig;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.Endpoint;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.Extension;
import jakarta.websocket.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class MonitorHubIntegrationTest {

    private static final ApplicationName TICKETS = new ApplicationName("tickets");
    private static final ApplicationName BILLING = new ApplicationName("billing");
    // Shut down rather than closed, which would wait for open WebSockets.
    private final HttpClient client = HttpClient.newHttpClient();
    private ConfigurableApplicationContext hub;
    private ConfigurableApplicationContext tickets;

    @BeforeEach
    void startHubAndTickets() {
        hub = new SpringApplicationBuilder(MonitorHubApplication.class)
            .properties("server.port=0", "server.address=127.0.0.1", "management.tracing.sampling.probability=0.0")
            .run();
        tickets = new SpringApplicationBuilder(TicketApplication.class).web(WebApplicationType.NONE).properties(recording("tickets")).run();
    }

    @AfterEach
    void stopHubAndTickets() {
        client.shutdownNow();
        tickets.close();
        hub.close();
    }

    @Test
    void traces_eventForwardedToAnotherApplication_isADeliveryOfItsOrigin() {
        // Billing compresses its export, as an exporter configured for gzip does.
        try (var billing = new SpringApplicationBuilder(BillingApplication.class).web(WebApplicationType.NONE).properties(recording("billing"))
            .properties("management.opentelemetry.tracing.export.otlp.compression=gzip")
            .run()) {
            openTicket();
            var published = awaitInvocation(TICKETS, invocation -> invocation.type().name().equals(TicketOpened.class.getName())
                && invocation.span().stage() instanceof Stage.Dispatch);
            var span = published.span().span();

            // A broker hands the event over with its message ID and metadata, which carries the trace context of its publication; the
            // consumer publishes it on its own bus.
            billing.getBean(EventPublisher.class).publish(new EventMessage<>(published.span().messageId(), Optional.empty(), new TicketOpened(),
                Metadata.withProperties(Map.of("traceparent", "00-" + span.traceId() + "-" + span.spanId() + "-01"))));

            var delivery = awaitInvocation(BILLING, invocation -> invocation.span().stage() instanceof Stage.Dispatch);
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(retained())
                .filteredOn(retained -> retained.invocation().id().equals(delivery.id()))
                .singleElement()
                .extracting(retained -> retained.parent().orElseThrow())
                .extracting(ParentSummary::application, ParentSummary::parent, ParentSummary::delivered)
                .containsExactly(TICKETS, published.id(), true));
        }
    }

    @Test
    void traces_malformedProtobuf_answersAnInvalidArgumentStatus() throws Exception {
        var response = post("application/x-protobuf", new byte[]{0x0A, 0x7F});

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(type -> assertThat(type).startsWith("application/x-protobuf"));
        // A google.rpc.Status: code 3, INVALID_ARGUMENT, then the reason.
        assertThat(response.body()).startsWith(0x08, 0x03, 0x12);
        assertThat(new String(response.body(), StandardCharsets.UTF_8)).contains("declares 127 bytes");
    }

    @Test
    void traces_json_isAnUnsupportedMediaType() throws Exception {
        var response = post("application/json", "{}".getBytes(StandardCharsets.UTF_8));

        assertThat(response.statusCode()).isEqualTo(415);
    }

    @Test
    void connect_invocationsArrive_sendsTheStatisticsWithAMovingSequence() throws Exception {
        openTicket();
        awaitInvocation(TICKETS, invocation -> invocation.type().name().equals(OpenTicket.class.getName()));
        var listener = new FrameListener();
        var socket = client.newWebSocketBuilder().buildAsync(URI.create(base("ws") + "/live"), listener).get(5, TimeUnit.SECONDS);
        var initial = listener.awaitFrame(frame -> true);

        openTicket();

        var update = listener.awaitFrame(frame -> frame.get("sequence").asLong() > initial.get("sequence").asLong());
        assertThat(initial.get("applications").get(0).get("name").asString()).isEqualTo("tickets");
        assertThat(initial.get("health").get("applications").get(0).get("retained").asInt()).isPositive();
        assertThat(initial.has("invocations")).isFalse();
        assertThat(update.get("applications").get(0).get("types").size()).isPositive();
        socket.abort();
    }

    @Test
    void connect_epochsPublished_sendsASnapshotAndThenTheChangesOfEachEpochNamingTheProtocol() throws Exception {
        var listener = new FrameListener();
        var socket = client.newWebSocketBuilder().buildAsync(URI.create(base("ws") + "/live"), listener).get(5, TimeUnit.SECONDS);

        listener.awaitFrame(frame -> true);
        listener.awaitFrame(frame -> true);

        assertThat(listener.messages).hasSizeGreaterThanOrEqualTo(2);
        assertThat(listener.messages.getFirst().get("type").asString()).isEqualTo("snapshot");
        var delta = listener.messages.get(1);
        assertThat(delta.get("type").asString()).isEqualTo("delta");
        assertThat(delta.get("base").asLong()).isEqualTo(listener.messages.getFirst().get("epoch").asLong());
        assertThat(delta.get("protocol").asInt()).isEqualTo(Protocol.VERSION);
        assertThat(delta.get("build").asString()).isEqualTo(Protocol.build());
        socket.abort();
    }

    // W4: the servlet container negotiates permessage-deflate with a client that offers it, as browsers do.
    @Test
    void connect_clientOffersPermessageDeflate_negotiatesIt() throws Exception {
        var container = ContainerProvider.getWebSocketContainer();
        var offered = ClientEndpointConfig.Builder.create().extensions(List.of(new PermessageDeflate())).build();

        try (var session = container.connectToServer(new Endpoint() {
            @Override
            public void onOpen(Session session, EndpointConfig config) {
            }
        }, offered, URI.create(base("ws") + "/live"))) {

            assertThat(session.getNegotiatedExtensions()).extracting(Extension::getName).contains("permessage-deflate");
        }
    }

    @Test
    void api_tape_listsTheRetainedInvocationsNewestFirstWithTheirParents() throws Exception {
        openTicket();
        awaitInvocation(TICKETS, invocation -> invocation.processingGroup().isPresent());

        var response = query("/api/tape?scope=tickets&kind=EVENT&limit=1");

        assertThat(response.statusCode()).isEqualTo(200);
        var page = new ObjectMapper().readTree(response.body());
        var latest = page.get("invocations").get(0);
        assertThat(latest.get("invocation").get("kind").asString()).isEqualTo("EVENT");
        assertThat(latest.get("parent").get("retained").asBoolean()).isTrue();
        assertThat(page.get("matching").asInt()).isGreaterThan(1);
        assertThat(page.get("next").asString()).isEqualTo(latest.get("invocation").get("sequence").asString());
    }

    @Test
    void api_typeNotNamedAsAType_answersABadRequestWithContext() throws Exception {
        var response = query("/api/types?type=OpenTicket");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("application::KIND:class", "OpenTicket");
    }

    @Test
    void api_invocationNoLongerRetained_answersNotFound() throws Exception {
        var response = query("/api/invocations/999999");

        assertThat(response.statusCode()).isEqualTo(404);
    }

    @Test
    void connect_namingAnApplication_receivesEveryApplication() throws Exception {
        openTicket();
        awaitInvocation(TICKETS, invocation -> true);
        var listener = new FrameListener();

        var socket = client.newWebSocketBuilder().buildAsync(URI.create(base("ws") + "/live?application=unknown"), listener).get(5, TimeUnit.SECONDS);

        var initial = listener.awaitFrame(frame -> true);
        assertThat(initial.get("applications").valueStream().map(application -> application.get("name").asString())).containsExactly("tickets");
        socket.abort();
    }

    @Test
    void connect_foreignOrigin_rejectsTheHandshake() {
        var handshake = client.newWebSocketBuilder().header("Origin", "https://untrusted.example").buildAsync(URI.create(base("ws") + "/live"), new FrameListener());

        assertThatThrownBy(() -> handshake.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(WebSocketHandshakeException.class);
    }

    @Test
    void resources_get_servesThePageAndItsAssetsAtTheRoot() throws Exception {
        var page = query("/");
        var stylesheet = query("/style.css");
        var script = query("/app.js");
        var missing = query("/missing.js");

        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.headers().firstValue("Content-Type")).hasValueSatisfying(type -> assertThat(type).startsWith("text/html"));
        assertThat(stylesheet.headers().firstValue("Content-Type")).hasValueSatisfying(type -> assertThat(type).startsWith("text/css"));
        assertThat(script.headers().firstValue("Content-Type")).hasValueSatisfying(type -> assertThat(type).startsWith("text/javascript"));
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(hub.getBean(MonitorHubProperties.class).memory()).isEqualTo(DataSize.ofMegabytes(256));
    }

    private void openTicket() {
        tickets.getBean(CommandGateway.class).send(new OpenTicket());
    }

    private Invocation awaitInvocation(ApplicationName application, Predicate<Invocation> predicate) {
        return await().atMost(Duration.ofSeconds(10)).until(() -> retained().stream()
            .map(RetainedInvocation::invocation)
            .filter(invocation -> invocation.application().equals(application))
            .filter(predicate)
            .findFirst(), Optional::isPresent).orElseThrow();
    }

    private List<RetainedInvocation> retained() {
        return hub().epoch().tape(new TapeQuery(InvocationFilter.EVERY_INVOCATION, Duration.ZERO, Optional.empty(), TapeQuery.MAX_LIMIT)).invocations();
    }

    private HttpResponse<String> query(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base("http") + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<byte[]> post(String contentType, byte[] body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base("http") + "/v1/traces"))
            .header("Content-Type", contentType)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build();
        return client.send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    private MonitorHub hub() {
        return hub.getBean(MonitorHub.class);
    }

    // An application that records its SMD spans and exports them to the hub.
    private String[] recording(String application) {
        return new String[]{"spring.application.name=" + application, "management.opentelemetry.resource-attributes.host.name=" + application + "-1",
            "management.tracing.sampling.probability=1.0", "management.opentelemetry.tracing.export.otlp.endpoint=" + base("http") + "/v1/traces",
            "management.opentelemetry.tracing.export.schedule-delay=50ms"};
    }

    private String base(String scheme) {
        return scheme + "://127.0.0.1:" + ((WebServerApplicationContext) hub).getWebServer().getPort();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EnableSMD(packages = "app.dodb.smd.monitor.hub.spring")
    static class TicketApplication {

        @Bean
        TicketHandlers ticketHandlers(EventPublisher events) {
            return new TicketHandlers(events);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EnableSMD(packages = "app.dodb.smd.monitor.hub.spring.billing")
    static class BillingApplication {
    }

    public static class TicketHandlers {

        private final EventPublisher events;

        public TicketHandlers(EventPublisher events) {
            this.events = events;
        }

        @CommandHandler
        public String handle(OpenTicket command) {
            events.publish(new TicketOpened());
            return "opened";
        }

        @EventHandler
        @ProcessingGroup("ticket-view")
        public void handle(TicketOpened event) {
        }
    }

    public record OpenTicket() implements Command<String> {
    }

    public record TicketOpened() implements Event {
    }

    private record PermessageDeflate() implements Extension {

        @Override
        public String getName() {
            return "permessage-deflate";
        }

        @Override
        public List<Parameter> getParameters() {
            return List.of();
        }
    }

    // Mirrors the frames as the page does: a snapshot replaces the frame, and a delta applies its changes to the frame of its base.
    private static class FrameListener implements WebSocket.Listener {
        private final LinkedBlockingQueue<String> frames = new LinkedBlockingQueue<>();
        private final StringBuilder fragment = new StringBuilder();
        private final List<JsonNode> messages = new ArrayList<>();
        private JsonNode mirror;

        @Override
        public CompletionStage<?> onText(WebSocket socket, CharSequence text, boolean last) {
            fragment.append(text);
            if (last) {
                frames.add(fragment.toString());
                fragment.setLength(0);
            }
            socket.request(1);
            return null;
        }

        private JsonNode awaitFrame(Predicate<JsonNode> predicate) throws InterruptedException {
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (true) {
                var text = frames.poll(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                assertThat(text).as("Matching WebSocket frame within ten seconds").isNotNull();
                var message = new ObjectMapper().readTree(text);
                messages.add(message);
                if (message.get("type").asString().equals("snapshot")) {
                    mirror = message.get("frame");
                } else {
                    assertThat(mirror).as("A delta follows a snapshot").isNotNull();
                    mirror = FrameDelta.apply(mirror, message.get("changes"), message.get("shift").asLong());
                }
                if (predicate.test(mirror)) {
                    return mirror;
                }
            }
        }
    }
}
