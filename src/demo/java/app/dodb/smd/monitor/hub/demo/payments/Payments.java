package app.dodb.smd.monitor.hub.demo.payments;

import app.dodb.smd.api.command.Command;
import app.dodb.smd.api.command.CommandGateway;
import app.dodb.smd.api.command.CommandHandler;
import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventHandler;
import app.dodb.smd.api.event.EventPublisher;
import app.dodb.smd.api.event.ProcessingGroup;
import app.dodb.smd.api.event.bus.ProcessingGroupsConfigurer;
import app.dodb.smd.api.query.Query;
import app.dodb.smd.api.query.QueryGateway;
import app.dodb.smd.api.query.QueryHandler;
import app.dodb.smd.monitor.hub.demo.Topic;
import app.dodb.smd.monitor.hub.demo.inventory.Inventory.StockReservedEvent;
import app.dodb.smd.spring.EnableSMD;
import app.dodb.smd.spring.EventBusSpecCustomizer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.util.concurrent.ThreadLocalRandom;

import static app.dodb.smd.monitor.hub.demo.HubDemo.awaitLatency;
import static app.dodb.smd.monitor.hub.demo.HubDemo.chance;

@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration
@EnableSMD(packages = "app.dodb.smd.monitor.hub.demo.payments")
@Import(Payments.PaymentHandlers.class)
public class Payments {

    @Bean
    public EventBusSpecCustomizer paymentsTopic() {
        return spec -> spec.sinks(Topic.PAYMENTS.inlet());
    }

    @Bean
    public ProcessingGroupsConfigurer paymentProcessingGroups() {
        return spec -> spec.processingGroup("payment-processing").source(Topic.STOCK.outlet());
    }

    public record PaymentHandlers(EventPublisher events, ObjectProvider<CommandGateway> commands, ObjectProvider<QueryGateway> queries) {

        // Reads the reserved orders from the topic inventory appends them to.
        @EventHandler
        @ProcessingGroup("payment-processing")
        public void charge(StockReservedEvent event) {
            commands.getObject().send(new AuthorizePaymentCommand(event.orderId(), event.customerId(), event.totalCents()));
        }

        // The card gateway mostly answers quickly, sometimes slowly, and now and then not at all before its timeout.
        @CommandHandler
        public void authorize(AuthorizePaymentCommand command) {
            var risk = queries.getObject().send(new ScoreFraudRiskQuery(command.customerId(), command.totalCents()));
            if (chance(2)) {
                awaitLatency(2_000);
                throw new PaymentGatewayTimeoutException("Card gateway did not answer within 2 s for order " + command.orderId());
            }
            awaitLatency(chance(8) ? ThreadLocalRandom.current().nextLong(400, 1_600) : ThreadLocalRandom.current().nextLong(40, 120));
            if (risk >= 92) {
                events.publish(new PaymentDeclinedEvent(command.orderId(), "Card declined"));
                return;
            }
            events.publish(new PaymentCapturedEvent(command.orderId(), command.totalCents()));
        }

        @QueryHandler
        public int score(ScoreFraudRiskQuery query) {
            awaitLatency(8, 25);
            return ThreadLocalRandom.current().nextInt(100);
        }
    }

    public record AuthorizePaymentCommand(long orderId, long customerId, long totalCents) implements Command<Void> {
    }

    public record ScoreFraudRiskQuery(long customerId, long totalCents) implements Query<Integer> {
    }

    public record PaymentCapturedEvent(long orderId, long totalCents) implements Event {
    }

    public record PaymentDeclinedEvent(long orderId, String reason) implements Event {
    }

    public static class PaymentGatewayTimeoutException extends RuntimeException {

        public PaymentGatewayTimeoutException(String message) {
            super(message);
        }
    }
}
