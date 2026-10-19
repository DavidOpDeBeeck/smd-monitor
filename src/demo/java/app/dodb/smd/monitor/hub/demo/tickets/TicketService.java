package app.dodb.smd.monitor.hub.demo.tickets;

import app.dodb.smd.api.command.Command;
import app.dodb.smd.api.command.CommandGateway;
import app.dodb.smd.api.command.CommandHandler;
import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventHandler;
import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.api.event.EventPublisher;
import app.dodb.smd.api.event.ProcessingGroup;
import app.dodb.smd.api.message.MessageId;
import app.dodb.smd.api.metadata.Metadata;
import app.dodb.smd.api.query.Query;
import app.dodb.smd.api.query.QueryGateway;
import app.dodb.smd.api.query.QueryHandler;
import app.dodb.smd.monitor.hub.demo.HubDemo;
import app.dodb.smd.monitor.hub.demo.SharedEventLog;
import app.dodb.smd.spring.EnableSMD;
import app.dodb.smd.spring.EventBusSpecCustomizer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;

import static app.dodb.smd.monitor.hub.demo.HubDemo.awaitLatency;

@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration
@EnableScheduling
@EnableSMD(packages = "app.dodb.smd.monitor.hub.demo.tickets")
@Import(TicketService.TicketHandlers.class)
public class TicketService {

    @Bean
    public EventBusSpecCustomizer sharedEventLog() {
        return spec -> spec.sinks(SharedEventLog.TICKETS.inlet());
    }

    // --tickets.smd.demo.tickets-per-second=N opens N more tickets every second, in parallel, so a hub with a small memory budget has to let go
    // of traces.
    public record TicketHandlers(EventPublisher events, ObjectProvider<CommandGateway> commands, ObjectProvider<QueryGateway> queries,
                                 @Value("${smd.demo.tickets-per-second:0}") int ticketsPerSecond) {

        @Scheduled(fixedRate = 1000, initialDelay = 1000)
        public void openTickets() {
            if (ticketsPerSecond == 0) {
                return;
            }
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                for (var ticket = 0; ticket < ticketsPerSecond; ticket++) {
                    executor.submit(this::openTicket);
                }
            }
        }

        @Scheduled(fixedDelay = 700, initialDelay = 1000)
        public void openTicket() {
            var ticketId = ThreadLocalRandom.current().nextLong(1, 1_000_000);
            try {
                commands.getObject().send(new OpenTicketCommand(ticketId));
            } catch (IllegalStateException expectedDemoFailure) {
                // The demo intentionally fails every ninth projection, to be inspected in the monitor.
            }
            queries.getObject().send(new GetTicketQuery(ticketId));
        }

        @CommandHandler
        public long open(OpenTicketCommand command) {
            awaitLatency(8);
            events.publish(new TicketOpenedEvent(command.ticketId()));
            return command.ticketId();
        }

        @QueryHandler
        public String find(GetTicketQuery query) {
            awaitLatency(2);
            return "Ticket " + query.ticketId();
        }

        // The metadata carries the trace context of the publication, so billing continues its trace.
        @EventHandler
        @ProcessingGroup("broker")
        public void forward(TicketOpenedEvent event, MessageId messageId, Metadata metadata) {
            HubDemo.BROKER.offer(new EventMessage<>(messageId, Optional.empty(), event, metadata));
        }

        @EventHandler
        @ProcessingGroup("ticket-projection")
        public void project(TicketOpenedEvent event) {
            awaitLatency(20);
            if (event.ticketId() % 9 == 0) {
                throw new IllegalStateException("Intentional demo projection failure");
            }
        }
    }

    public record OpenTicketCommand(long ticketId) implements Command<Long> {
    }

    public record GetTicketQuery(long ticketId) implements Query<String> {
    }

    public record TicketOpenedEvent(long ticketId) implements Event {
    }
}
