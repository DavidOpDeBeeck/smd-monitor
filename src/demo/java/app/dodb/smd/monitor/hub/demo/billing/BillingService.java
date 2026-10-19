package app.dodb.smd.monitor.hub.demo.billing;

import app.dodb.smd.api.command.Command;
import app.dodb.smd.api.command.CommandGateway;
import app.dodb.smd.api.command.CommandHandler;
import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventHandler;
import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.api.event.EventPublisher;
import app.dodb.smd.api.event.ProcessingGroup;
import app.dodb.smd.api.event.bus.ProcessingGroupsConfigurer;
import app.dodb.smd.monitor.hub.demo.HubDemo;
import app.dodb.smd.monitor.hub.demo.SharedEventLog;
import app.dodb.smd.monitor.hub.demo.tickets.TicketService.TicketOpenedEvent;
import app.dodb.smd.spring.EnableSMD;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.ArrayList;
import java.util.concurrent.ThreadLocalRandom;

import static app.dodb.smd.monitor.hub.demo.HubDemo.awaitLatency;

@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration
@EnableScheduling
@EnableSMD(packages = "app.dodb.smd.monitor.hub.demo.billing")
@Import(BillingService.BillingHandlers.class)
public class BillingService {

    @Bean
    public ProcessingGroupsConfigurer billingProcessingGroups() {
        return spec -> spec
            .processingGroup("invoicing").async().await()
            .processingGroup("ledger").async().fireAndForget()
            .processingGroup("ticket-audit").source(SharedEventLog.TICKETS.outlet());
    }

    public record BillingHandlers(EventPublisher events, ObjectProvider<CommandGateway> commands) {

        @Scheduled(fixedDelay = 250)
        public void receiveFromBroker() {
            var messages = new ArrayList<EventMessage<?>>();
            HubDemo.BROKER.drainTo(messages);
            messages.forEach(events::publish);
        }

        @EventHandler
        @ProcessingGroup("invoicing")
        public void invoice(TicketOpenedEvent event) {
            try {
                commands.getObject().send(new ChargeInvoiceCommand(event.ticketId()));
            } catch (IllegalStateException expectedDemoFailure) {
                // The demo intentionally fails every fifth payment, to be inspected in the monitor.
            }
        }

        @CommandHandler
        public void charge(ChargeInvoiceCommand command) {
            awaitLatency(30 + ThreadLocalRandom.current().nextLong(1000));
            if (command.invoiceId() % 5 == 0) {
                throw new IllegalStateException("Intentional demo payment failure");
            }
            events.publish(new InvoiceChargedEvent(command.invoiceId()));
        }

        // Reads the opened tickets from the log ticket-service appends them to, without billing publishing them.
        @EventHandler
        @ProcessingGroup("ticket-audit")
        public void audit(TicketOpenedEvent event) {
            awaitLatency(3);
        }

        @EventHandler
        @ProcessingGroup("ledger")
        public void record(InvoiceChargedEvent event) {
            awaitLatency(12 + ThreadLocalRandom.current().nextLong(1000));
        }
    }

    public record ChargeInvoiceCommand(long invoiceId) implements Command<Void> {
    }

    public record InvoiceChargedEvent(long invoiceId) implements Event {
    }
}
