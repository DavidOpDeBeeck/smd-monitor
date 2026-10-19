package app.dodb.smd.monitor.hub.demo.shipping;

import app.dodb.smd.api.command.Command;
import app.dodb.smd.api.command.CommandGateway;
import app.dodb.smd.api.command.CommandHandler;
import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventHandler;
import app.dodb.smd.api.event.EventPublisher;
import app.dodb.smd.api.event.ProcessingGroup;
import app.dodb.smd.api.event.bus.ProcessingGroupsConfigurer;
import app.dodb.smd.monitor.hub.demo.Topic;
import app.dodb.smd.monitor.hub.demo.payments.Payments.PaymentCapturedEvent;
import app.dodb.smd.spring.EnableSMD;
import app.dodb.smd.spring.EventBusSpecCustomizer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static app.dodb.smd.monitor.hub.demo.HubDemo.awaitLatency;
import static app.dodb.smd.monitor.hub.demo.HubDemo.chance;

@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration
@EnableSMD(packages = "app.dodb.smd.monitor.hub.demo.shipping")
@Import(Shipping.ShippingHandlers.class)
public class Shipping {

    private static final List<String> CARRIERS = List.of("parcel-express", "city-courier", "post");

    @Bean
    public EventBusSpecCustomizer shipmentsTopic() {
        return spec -> spec.sinks(Topic.SHIPMENTS.inlet());
    }

    @Bean
    public ProcessingGroupsConfigurer shippingProcessingGroups() {
        return spec -> spec
            .processingGroup("fulfilment").source(Topic.PAYMENTS.outlet())
            .processingGroup("carrier-labels").async().fireAndForget();
    }

    public record ShippingHandlers(EventPublisher events, ObjectProvider<CommandGateway> commands) {

        // Reads the captured payments from the topic payments appends them to.
        @EventHandler
        @ProcessingGroup("fulfilment")
        public void ship(PaymentCapturedEvent event) {
            commands.getObject().send(new ScheduleShipmentCommand(event.orderId()));
        }

        @CommandHandler
        public void schedule(ScheduleShipmentCommand command) {
            awaitLatency(10, 30);
            events.publish(new ShipmentScheduledEvent(command.orderId(), CARRIERS.get(ThreadLocalRandom.current().nextInt(CARRIERS.size()))));
        }

        // Fire and forget: a carrier that is down fails the label, not the shipment.
        @EventHandler
        @ProcessingGroup("carrier-labels")
        public void printLabel(ShipmentScheduledEvent event) {
            awaitLatency(80, 300);
            if (chance(5)) {
                throw new CarrierUnavailableException("Carrier " + event.carrier() + " is unavailable");
            }
        }
    }

    public record ScheduleShipmentCommand(long orderId) implements Command<Void> {
    }

    public record ShipmentScheduledEvent(long orderId, String carrier) implements Event {
    }

    public static class CarrierUnavailableException extends RuntimeException {

        public CarrierUnavailableException(String message) {
            super(message);
        }
    }
}
