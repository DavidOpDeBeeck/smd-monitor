package app.dodb.smd.monitor.hub.otlp;

import app.dodb.smd.api.command.Command;
import app.dodb.smd.api.command.CommandHandlerContext;
import app.dodb.smd.api.command.CommandHandlerInterceptorChain;
import app.dodb.smd.api.command.CommandMessage;
import app.dodb.smd.api.command.bus.CommandBusContext;
import app.dodb.smd.api.command.bus.CommandBusInterceptorChain;
import app.dodb.smd.api.event.AnnotatedEventHandler;
import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventHandler;
import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.api.event.ProcessingGroup;
import app.dodb.smd.api.event.ProcessingGroupContext;
import app.dodb.smd.api.event.ProcessingGroupInterceptorChain;
import app.dodb.smd.api.event.bus.EventBusContext;
import app.dodb.smd.api.event.bus.EventBusInterceptorChain;
import app.dodb.smd.api.event.bus.EventBusSpec;
import app.dodb.smd.api.event.delivery.EventDelivery;
import app.dodb.smd.api.event.delivery.EventSink;
import app.dodb.smd.api.message.Message;
import app.dodb.smd.api.metadata.Metadata;
import app.dodb.smd.api.query.Query;
import app.dodb.smd.api.query.QueryHandlerContext;
import app.dodb.smd.api.query.QueryHandlerInterceptorChain;
import app.dodb.smd.api.query.QueryMessage;
import app.dodb.smd.api.query.bus.QueryBusContext;
import app.dodb.smd.api.query.bus.QueryBusInterceptorChain;
import app.dodb.smd.monitor.hub.ApplicationStatistics;
import app.dodb.smd.monitor.hub.GroupStatistics;
import app.dodb.smd.monitor.hub.HubConfig;
import app.dodb.smd.monitor.hub.Instance;
import app.dodb.smd.monitor.hub.Invocation;
import app.dodb.smd.monitor.hub.InvocationFilter;
import app.dodb.smd.monitor.hub.InvocationId;
import app.dodb.smd.monitor.hub.LinkStatistics;
import app.dodb.smd.monitor.hub.RetainedInvocation;
import app.dodb.smd.monitor.hub.SinkRouteStatistics;
import app.dodb.smd.monitor.hub.SynchronousHub;
import app.dodb.smd.monitor.hub.TapeQuery;
import app.dodb.smd.monitor.hub.TypeStatistics;
import app.dodb.smd.monitor.hub.catalog.CausalLink;
import app.dodb.smd.monitor.hub.catalog.SinkRoute;
import app.dodb.smd.monitor.hub.intake.SpanBatch;
import app.dodb.smd.monitor.hub.span.ApplicationName;
import app.dodb.smd.monitor.hub.span.InstanceName;
import app.dodb.smd.monitor.hub.span.MessageKind;
import app.dodb.smd.monitor.hub.span.MessageType;
import app.dodb.smd.monitor.hub.span.Outcome;
import app.dodb.smd.monitor.hub.span.SessionId;
import app.dodb.smd.opentelemetry.MessageSpans;
import app.dodb.smd.opentelemetry.MessageTracing;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.exporter.internal.otlp.traces.TraceRequestMarshaler;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

class OtlpTracesTest {

    private static final ApplicationName TICKETS = new ApplicationName("ticket-service");
    private static final Resource RESOURCE = Resource.create(Attributes.of(SpanReader.SERVICE_NAME, TICKETS.value(), SpanReader.HOST_NAME, "tickets-1",
        SpanReader.SERVICE_INSTANCE_ID, "6d0f3b9e-instance"));
    private final InMemorySpanExporter spans = InMemorySpanExporter.create();
    private final SynchronousHub hub = new SynchronousHub(HubConfig.withDefaults().build());

    @Test
    void export_requestOfTheOtlpExporter_reachesTheHubAsTheInvocationsOfItsInstance() {
        var recording = recording(RESOURCE);
        send(recording, open(), received -> send(recording, CommandMessage.from(new AssignTicket(), sentFrom(received)), nothing -> null));
        var assignDispatch = spans.getFinishedSpanItems().get(1);

        var response = export(encoded());

        var invocations = invocations();
        assertThat(response).isEmpty();
        assertThat(statistics().instances()).extracting(Instance::name, Instance::session)
            .containsExactly(tuple(new InstanceName("tickets-1"), new SessionId("6d0f3b9e-instance")));
        assertThat(invocations).extracting(Invocation::type, Invocation::handling, Invocation::processingGroup).containsExactly(
            tuple(type(AssignTicket.class), true, Optional.empty()),
            tuple(type(AssignTicket.class), false, Optional.empty()),
            tuple(type(OpenTicket.class), true, Optional.empty()),
            tuple(type(OpenTicket.class), false, Optional.empty()));
        assertThat(invocations.get(1)).satisfies(assign -> {
            assertThat(assign.span().threadName()).isEqualTo(Thread.currentThread().getName());
            assertThat(assign.startedAt()).isEqualTo(Instant.ofEpochSecond(0, assignDispatch.getStartEpochNanos()));
            assertThat(assign.span().outcome())
                .isEqualTo(new Outcome.Completed(Duration.ofNanos(assignDispatch.getEndEpochNanos() - assignDispatch.getStartEpochNanos())));
        });
        assertThat(parents()).containsExactly(tuple(id(1), id(2)), tuple(id(2), id(4)), tuple(id(4), id(6)));
        assertThat(statistics().links()).extracting(LinkStatistics::link).containsExactly(new CausalLink(type(OpenTicket.class), type(AssignTicket.class)));
        assertThat(statistics().types()).extracting(TypeStatistics::type, TypeStatistics::entryPoint)
            .containsExactlyInAnyOrder(tuple(type(OpenTicket.class), true), tuple(type(AssignTicket.class), false));
    }

    @Test
    void export_childDispatchedWithinASpanOfTheApplication_findsTheHandlingItLinksTo() {
        var provider = tracerProvider(RESOURCE);
        var recording = new MessageTracing(openTelemetry(provider));
        send(recording, open(), received -> {
            var service = provider.get("application").spanBuilder("service").startSpan();
            try (var ignored = service.makeCurrent()) {
                return send(recording, CommandMessage.from(new AssignTicket(), sentFrom(received)), nothing -> null);
            } finally {
                service.end();
            }
        });

        export(encoded());

        assertThat(parents()).containsExactly(tuple(id(1), id(2)), tuple(id(2), id(4)), tuple(id(4), id(6)));
        assertThat(statistics().links()).extracting(LinkStatistics::link).containsExactly(new CausalLink(type(OpenTicket.class), type(AssignTicket.class)));
    }

    @Test
    void export_dispatchNestedInTheSpanOfAnotherMessage_findsTheInvocationItLinksTo() {
        var recording = recording(RESOURCE);
        var earlier = new AtomicReference<CommandMessage<Object, OpenTicket>>();
        send(recording, open(), received -> {
            earlier.set(received);
            return null;
        });
        send(recording, CommandMessage.from(new CloseTicket(), Metadata.withProperties(Map.of())),
            closing -> send(recording, CommandMessage.from(new AssignTicket(), sentFrom(earlier.get())), nothing -> null));

        export(encoded());

        assertThat(retained()).filteredOn(retained -> retained.invocation().type().equals(type(AssignTicket.class)) && !retained.invocation().handling())
            .singleElement()
            .satisfies(assign -> assertThat(assign.parent()).hasValueSatisfying(link -> {
                assertThat(link.type()).isEqualTo(type(OpenTicket.class));
                assertThat(link.handling()).isTrue();
            }));
        assertThat(statistics().links()).extracting(LinkStatistics::link).containsExactly(new CausalLink(type(OpenTicket.class), type(AssignTicket.class)));
    }

    @Test
    void export_queryDispatchedInACommandHandling_linksTheCommandToTheQuery() {
        var recording = recording(RESOURCE);
        var query = new MessageType(MessageKind.QUERY, AvailableAgent.class.getName());
        send(recording, open(), received -> recording.intercept(new QueryBusContext<>(QueryMessage.from(new AvailableAgent(), sentFrom(received))),
            QueryBusInterceptorChain.<Object, AvailableAgent>create(sent -> recording.intercept(new QueryHandlerContext<>(sent.queryMessage()),
                QueryHandlerInterceptorChain.<Object, AvailableAgent>create(nothing -> "agent", List.of())), List.of())));

        export(encoded());

        assertThat(invocations()).extracting(Invocation::type, Invocation::handling)
            .containsExactly(tuple(query, true), tuple(query, false), tuple(type(OpenTicket.class), true), tuple(type(OpenTicket.class), false));
        assertThat(statistics().links()).extracting(LinkStatistics::link).containsExactly(new CausalLink(type(OpenTicket.class), query));
        assertThat(statistics().groups()).isEmpty();
    }

    @Test
    void export_eventHandledByAProcessingGroup_reportsTheDeliveryOfTheGroup() {
        var recording = recording(RESOURCE);
        publish(recording, new EventDelivery("event-store"));

        var response = export(encoded());

        assertThat(response).isEmpty();
        assertThat(statistics().groups()).extracting(GroupStatistics::processingGroup, GroupStatistics::delivery)
            .containsExactly(tuple("ticket-activity", new EventDelivery("event-store")));
    }

    @Test
    void export_publicationThroughTheEventBus_routesTheEventToTheDeliveryOfEachSinkWithoutRetainingTheSends() {
        var recording = recording(RESOURCE);
        var eventBus = EventBusSpec.withDefaults()
            .sinks(new SharedLog())
            .processingGroups(() -> AnnotatedEventHandler.from(new TicketActivity()), groups -> groups.processingGroup("ticket-activity").sync())
            .interceptors(recording)
            .sinkInterceptors(recording)
            .create();
        eventBus.publish(new TicketOpened());

        var response = export(encoded());

        var opened = new MessageType(MessageKind.EVENT, TicketOpened.class.getName());
        assertThat(response).isEmpty();
        assertThat(statistics().sinkRoutes()).extracting(SinkRouteStatistics::route)
            .containsExactly(new SinkRoute(opened, new EventDelivery("shared-log")), new SinkRoute(opened, EventDelivery.SYNC));
        assertThat(invocations()).extracting(Invocation::type).containsExactly(opened);
    }

    @Test
    void export_publicationSentToNoSink_reachesTheHubWithoutSinkRoutes() {
        var recording = recording(RESOURCE);
        recording.intercept(new EventBusContext<>(EventMessage.from(new TicketOpened(), Metadata.withProperties(Map.of()))), EventBusInterceptorChain.create(ignored -> {
        }, List.of()));

        var response = export(encoded());

        assertThat(response).isEmpty();
        assertThat(invocations()).hasSize(1);
        assertThat(statistics().sinkRoutes()).isEmpty();
    }

    @Test
    void export_sinkSendWithoutDelivery_answersAPartialSuccessNamingTheAttribute() {
        var send = tracerProvider(RESOURCE).get(MessageSpans.INSTRUMENTATION_SCOPE).spanBuilder("send TicketOpened")
            .setAttribute(MessageSpans.MESSAGE_KIND, MessageSpans.EVENT)
            .setAttribute(MessageSpans.OPERATION_TYPE, MessageSpans.SEND)
            .setAttribute(MessageSpans.OPERATION_NAME, MessageSpans.SEND)
            .setAttribute(MessageSpans.DESTINATION_NAME, TicketOpened.class.getName())
            .setAttribute(MessageSpans.MESSAGE_ID, "6d0f3b9e-1c1a-4f2e-9a57-0b6d6f2b8c11")
            .setAttribute(MessageSpans.THREAD_NAME, "main")
            .startSpan();
        send.end();

        var partialSuccess = ProtobufMessage.parse(export(encoded())).message(1).orElseThrow();

        assertThat(partialSuccess.number(1)).isEqualTo(1);
        assertThat(partialSuccess.string(2)).hasValueSatisfying(reason -> assertThat(reason).contains(send.getSpanContext().getSpanId(),
            TicketOpened.class.getName(), "messaging.smd.delivery"));
        assertThat(hub.statistics().applications()).isEmpty();
    }

    @Test
    void export_groupHandlingWithoutDelivery_answersAPartialSuccessNamingTheAttribute() {
        var handling = tracerProvider(RESOURCE).get(MessageSpans.INSTRUMENTATION_SCOPE).spanBuilder("process TicketOpened")
            .setAttribute(MessageSpans.MESSAGE_KIND, MessageSpans.EVENT)
            .setAttribute(MessageSpans.OPERATION_TYPE, MessageSpans.PROCESS)
            .setAttribute(MessageSpans.DESTINATION_NAME, TicketOpened.class.getName())
            .setAttribute(MessageSpans.PROCESSING_GROUP, "ticket-activity")
            .setAttribute(MessageSpans.MESSAGE_ID, "6d0f3b9e-1c1a-4f2e-9a57-0b6d6f2b8c11")
            .setAttribute(MessageSpans.THREAD_NAME, "main")
            .startSpan();
        handling.end();

        var partialSuccess = ProtobufMessage.parse(export(encoded())).message(1).orElseThrow();

        assertThat(partialSuccess.number(1)).isEqualTo(1);
        assertThat(partialSuccess.string(2)).hasValueSatisfying(reason -> assertThat(reason).contains(handling.getSpanContext().getSpanId(), "ticket-activity",
            "messaging.smd.delivery"));
        assertThat(hub.statistics().applications()).isEmpty();
    }

    @Test
    void export_failedSpan_keepsItsFailureType() {
        var recording = recording(RESOURCE);
        assertThatThrownBy(() -> send(recording, open(), ignored -> {
            throw new IllegalStateException("blocked");
        }));

        export(encoded());

        assertThat(invocations()).extracting(invocation -> invocation.span().outcome()).hasSize(2)
            .allSatisfy(outcome -> assertThat(outcome).isInstanceOfSatisfying(Outcome.Failed.class,
                failed -> assertThat(failed.failureType()).isEqualTo(IllegalStateException.class.getName())));
    }

    @Test
    void export_failedSpanWithoutAnErrorType_keepsItAsAFailureOfUnknownType() {
        var dispatch = tracerProvider(RESOURCE).get(MessageSpans.INSTRUMENTATION_SCOPE).spanBuilder("send OpenTicket")
            .setAttribute(MessageSpans.MESSAGE_KIND, MessageSpans.COMMAND)
            .setAttribute(MessageSpans.OPERATION_TYPE, MessageSpans.SEND)
            .setAttribute(MessageSpans.OPERATION_NAME, MessageSpans.SEND)
            .setAttribute(MessageSpans.DESTINATION_NAME, OpenTicket.class.getName())
            .setAttribute(MessageSpans.MESSAGE_ID, "6d0f3b9e-1c1a-4f2e-9a57-0b6d6f2b8c11")
            .setAttribute(MessageSpans.THREAD_NAME, "main")
            .startSpan();
        dispatch.setStatus(StatusCode.ERROR);
        dispatch.end();

        var response = export(encoded());

        assertThat(response).isEmpty();
        assertThat(invocations()).singleElement().satisfies(invocation -> assertThat(invocation.span().outcome())
            .isInstanceOfSatisfying(Outcome.Failed.class, failed -> assertThat(failed.failureType()).isEqualTo(SpanReader.UNKNOWN_FAILURE_TYPE)));
    }

    @Test
    void export_hostNameThatIsNoInstanceName_rejectsItsSpansNamingTheResourceAttribute() {
        recording(Resource.create(Attributes.of(SpanReader.SERVICE_NAME, TICKETS.value(), SpanReader.HOST_NAME, "my host")))
            .intercept(new CommandBusContext<>(open()), CommandBusInterceptorChain.create(ignored -> null, List.of()));

        var partialSuccess = ProtobufMessage.parse(export(encoded())).message(1).orElseThrow();

        assertThat(partialSuccess.number(1)).isEqualTo(1);
        assertThat(partialSuccess.string(2)).hasValueSatisfying(reason -> assertThat(reason).contains("host.name", "'my host'"));
        assertThat(hub.statistics().applications()).isEmpty();
    }

    @Test
    void export_spansOfOtherInstrumentation_areIgnored() {
        tracerProvider(RESOURCE).get("io.opentelemetry.http").spanBuilder("GET /tickets").startSpan().end();

        var response = export(encoded());

        assertThat(response).isEmpty();
        assertThat(hub.statistics().applications()).isEmpty();
    }

    @Test
    void export_smdSpanWithoutItsAttributes_answersAPartialSuccessWithItsReason() {
        var recording = recording(RESOURCE);
        tracerProvider(RESOURCE).get(MessageSpans.INSTRUMENTATION_SCOPE).spanBuilder("send Something").startSpan().end();
        send(recording, open(), ignored -> null);

        var partialSuccess = ProtobufMessage.parse(export(encoded())).message(1).orElseThrow();

        assertThat(partialSuccess.number(1)).isEqualTo(1);
        assertThat(partialSuccess.string(2)).hasValueSatisfying(reason -> assertThat(reason).contains(spans.getFinishedSpanItems().getFirst().getSpanId(),
            "messaging.smd.message.kind"));
        assertThat(invocations()).hasSize(2);
    }

    @Test
    void export_resourceNamingNoInstance_reportsAsTheUnnamedInstance() {
        recording(Resource.create(Attributes.of(SpanReader.SERVICE_NAME, TICKETS.value())))
            .intercept(new CommandBusContext<>(open()), CommandBusInterceptorChain.create(ignored -> null, List.of()));

        var response = export(encoded());

        assertThat(response).isEmpty();
        assertThat(statistics().instances()).extracting(Instance::name, Instance::session)
            .containsExactly(tuple(SpanReader.UNNAMED_INSTANCE, new SessionId("unnamed")));
    }

    @Test
    void export_resourceWithoutServiceName_rejectsItsSpans() {
        recording(Resource.create(Attributes.of(SpanReader.HOST_NAME, "tickets-1")))
            .intercept(new CommandBusContext<>(open()), CommandBusInterceptorChain.create(ignored -> null, List.of()));

        var partialSuccess = ProtobufMessage.parse(export(encoded())).message(1).orElseThrow();

        assertThat(partialSuccess.number(1)).isEqualTo(1);
        assertThat(partialSuccess.string(2)).hasValueSatisfying(reason -> assertThat(reason).contains("service.name"));
        assertThat(hub.statistics().applications()).isEmpty();
    }

    @Test
    void export_applicationBeyondTheHubsLimit_answersAPartialSuccessCountingEveryOneOfItsSpans() {
        var hub = new SynchronousHub(HubConfig.withDefaults().maxApplications(1).build());
        hub.receive(new SpanBatch(new ApplicationName("billing-service"), new InstanceName("billing-1"), new SessionId("billing-1"), List.of(), List.of()));
        var recording = recording(RESOURCE);
        EventBusSpec.withDefaults()
            .sinks(new SharedLog())
            .processingGroups(() -> AnnotatedEventHandler.from(new TicketActivity()), groups -> groups.processingGroup("ticket-activity").sync())
            .interceptors(recording)
            .sinkInterceptors(recording)
            .create()
            .publish(new TicketOpened());

        var partialSuccess = ProtobufMessage.parse(export(encoded(), hub)).message(1).orElseThrow();

        assertThat(spans.getFinishedSpanItems()).hasSizeGreaterThan(1);
        assertThat(partialSuccess.number(1)).isEqualTo(spans.getFinishedSpanItems().size());
        assertThat(partialSuccess.string(2)).hasValueSatisfying(reason -> assertThat(reason).contains("already monitors 1 applications", TICKETS.value()));
    }

    @Test
    void export_truncatedRequest_failsWithContextBeforeTheHubReceivesAnything() {
        recording(RESOURCE).intercept(new CommandBusContext<>(open()), CommandBusInterceptorChain.create(ignored -> null, List.of()));
        var body = encoded();

        assertThatThrownBy(() -> OtlpTraces.read(Arrays.copyOf(body, body.length - 5)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Protobuf");
        assertThat(hub.statistics().applications()).isEmpty();
    }

    // A command bus: its dispatch, and within it the handling.
    private static <C extends Command<Object>> Object send(MessageTracing tracing, CommandMessage<Object, C> message,
                                                           Function<CommandMessage<Object, C>, Object> handler) {
        return tracing.intercept(new CommandBusContext<>(message), CommandBusInterceptorChain.<Object, C>create(
            sent -> tracing.intercept(new CommandHandlerContext<>(sent.commandMessage()),
                CommandHandlerInterceptorChain.<Object, C>create(handled -> handler.apply(handled.commandMessage()), List.of())), List.of()));
    }

    // An event bus: the publication, and within it the handling of the group ticket-activity.
    private static void publish(MessageTracing tracing, EventDelivery delivery) {
        tracing.intercept(new EventBusContext<>(EventMessage.from(new TicketOpened(), Metadata.withProperties(Map.of()))), EventBusInterceptorChain.<TicketOpened>create(
            published -> tracing.intercept(new ProcessingGroupContext<>("ticket-activity", delivery, published.eventMessage()),
                ProcessingGroupInterceptorChain.<TicketOpened>create(ignored -> {
                }, List.of())), List.of()));
    }

    private static CommandMessage<Object, OpenTicket> open() {
        return CommandMessage.from(new OpenTicket(), Metadata.withProperties(Map.of()));
    }

    // The metadata SMD gives a message a handler sends.
    private static Metadata sentFrom(Message<?, ?> handled) {
        return new Metadata(null, null, handled.messageId(), handled.metadata().properties());
    }

    private MessageTracing recording(Resource resource) {
        return new MessageTracing(openTelemetry(tracerProvider(resource)));
    }

    private static OpenTelemetrySdk openTelemetry(SdkTracerProvider provider) {
        return OpenTelemetrySdk.builder().setTracerProvider(provider).setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance())).build();
    }

    private SdkTracerProvider tracerProvider(Resource resource) {
        return SdkTracerProvider.builder().setResource(resource).addSpanProcessor(SimpleSpanProcessor.create(spans)).build();
    }

    private byte[] export(byte[] request) {
        return export(request, hub);
    }

    // Submits as the controller does, and answers what the hub refused.
    private static byte[] export(byte[] request, SynchronousHub hub) {
        var intake = OtlpTraces.read(request);
        assertThat(intake.submit(hub::submit)).isEmpty();
        return OtlpTraces.response(intake.rejected());
    }

    private byte[] encoded() {
        var output = new ByteArrayOutputStream();
        try {
            TraceRequestMarshaler.create(spans.getFinishedSpanItems()).writeBinaryTo(output);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        return output.toByteArray();
    }

    // Oldest first.
    private List<RetainedInvocation> retained() {
        return hub.tape(new TapeQuery(InvocationFilter.EVERY_INVOCATION, Duration.ZERO, Optional.empty(), TapeQuery.MAX_LIMIT)).invocations().reversed();
    }

    // Each child with its parent.
    private List<Tuple> parents() {
        return retained().stream()
            .filter(retained -> retained.parent().isPresent())
            .map(retained -> tuple(retained.invocation().id(), retained.parent().orElseThrow().parent()))
            .toList();
    }

    private static InvocationId id(long value) {
        return new InvocationId(value);
    }

    private List<Invocation> invocations() {
        return retained().stream().map(RetainedInvocation::invocation).toList();
    }

    private ApplicationStatistics statistics() {
        return hub.statistics().application(TICKETS).orElseThrow();
    }

    private static MessageType type(Class<?> command) {
        return new MessageType(MessageKind.COMMAND, command.getName());
    }

    private record OpenTicket() implements Command<Object> {
    }

    private record AssignTicket() implements Command<Object> {
    }

    private record CloseTicket() implements Command<Object> {
    }

    private record AvailableAgent() implements Query<Object> {
    }

    public record TicketOpened() implements Event {
    }

    @ProcessingGroup("ticket-activity")
    public static class TicketActivity {

        @EventHandler
        public void on(TicketOpened event) {
        }
    }

    private static class SharedLog implements EventSink {

        @Override
        public <E extends Event> void send(EventMessage<E> eventMessage) {
        }

        @Override
        public EventDelivery delivery() {
            return new EventDelivery("shared-log");
        }
    }
}
