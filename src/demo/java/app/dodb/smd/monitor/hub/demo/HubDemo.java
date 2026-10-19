package app.dodb.smd.monitor.hub.demo;

import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.monitor.hub.demo.billing.BillingService;
import app.dodb.smd.monitor.hub.demo.tickets.TicketService;
import app.dodb.smd.monitor.hub.spring.MonitorHubApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

import java.util.Arrays;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Stream;

public class HubDemo {

    // An in-memory broker that carries opened tickets, with their message ID and metadata, from tickets to billing.
    public static final LinkedBlockingQueue<EventMessage<?>> BROKER = new LinkedBlockingQueue<>(10_000);

    // Arguments starting with --tickets. or --billing. configure that service, such as --billing.management.opentelemetry.tracing.export.schedule-delay=8s
    // to make it export later than the hub's settle; the others configure the hub.
    private static final String TICKETS = "--tickets.";
    private static final String BILLING = "--billing.";

    public static void main(String[] args) {
        var hub = new SpringApplicationBuilder(MonitorHubApplication.class)
            .properties("server.address=127.0.0.1", "server.port=8080", "spring.application.name=smd-monitor-hub",
                "management.tracing.sampling.probability=0.0")
            .run(Arrays.stream(args).filter(arg -> !arg.startsWith(TICKETS) && !arg.startsWith(BILLING)).toArray(String[]::new));
        var traces = "http://127.0.0.1:" + hub.getEnvironment().getProperty("local.server.port") + "/v1/traces";
        start(TicketService.class, "ticket-service", "tickets-1", traces, args, TICKETS);
        start(BillingService.class, "billing-service", "billing-1", traces, args, BILLING);
    }

    private static void start(Class<?> service, String application, String host, String traces, String[] args, String prefix) {
        var serviceArgs = Arrays.stream(args).filter(arg -> arg.startsWith(prefix)).map(arg -> "--" + arg.substring(prefix.length()));
        new SpringApplicationBuilder(service)
            .web(WebApplicationType.NONE)
            .properties("spring.application.name=" + application, "management.opentelemetry.resource-attributes.host.name=" + host,
                "management.tracing.sampling.probability=1.0", "management.opentelemetry.tracing.export.otlp.endpoint=" + traces,
                "management.opentelemetry.tracing.export.schedule-delay=1s", "spring.main.banner-mode=off")
            // One JVM holds the hub and both services, and only one may register the admin MBean an IDE enables through a system property; a
            // command-line argument outranks it.
            .run(Stream.concat(Stream.of("--spring.application.admin.enabled=false"), serviceArgs).toArray(String[]::new));
    }

    public static void awaitLatency(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Demo interrupted", failure);
        }
    }
}
