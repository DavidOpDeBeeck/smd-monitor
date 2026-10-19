package app.dodb.smd.monitor.hub.demo.inventory;

import app.dodb.smd.api.command.Command;
import app.dodb.smd.api.command.CommandGateway;
import app.dodb.smd.api.command.CommandHandler;
import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventHandler;
import app.dodb.smd.api.event.EventPublisher;
import app.dodb.smd.api.event.ProcessingGroup;
import app.dodb.smd.api.event.bus.ProcessingGroupsConfigurer;
import app.dodb.smd.monitor.hub.demo.Topic;
import app.dodb.smd.monitor.hub.demo.storefront.Storefront.OrderCancelledEvent;
import app.dodb.smd.monitor.hub.demo.storefront.Storefront.OrderPlacedEvent;
import app.dodb.smd.spring.EnableSMD;
import app.dodb.smd.spring.EventBusSpecCustomizer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static app.dodb.smd.monitor.hub.demo.HubDemo.awaitLatency;
import static app.dodb.smd.monitor.hub.demo.HubDemo.chance;

@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration
@EnableSMD(packages = "app.dodb.smd.monitor.hub.demo.inventory")
@Import(Inventory.InventoryHandlers.class)
public class Inventory {

    @Bean
    public EventBusSpecCustomizer stockTopic() {
        return spec -> spec.sinks(Topic.STOCK.inlet());
    }

    @Bean
    public ProcessingGroupsConfigurer inventoryProcessingGroups() {
        return spec -> spec
            .processingGroup("stock-reservation").source(Topic.ORDERS.outlet())
            .processingGroup("stock-release").source(Topic.ORDERS.outlet())
            .processingGroup("replenishment").async().await();
    }

    public record InventoryHandlers(EventPublisher events, ObjectProvider<CommandGateway> commands) {

        @EventHandler
        @ProcessingGroup("stock-reservation")
        public void reserve(OrderPlacedEvent event) {
            commands.getObject().send(new ReserveStockCommand(event.orderId(), event.customerId(), event.totalCents()));
        }

        @CommandHandler
        public void reserve(ReserveStockCommand command) {
            awaitLatency(4, 12);
            if (chance(3)) {
                throw new OutOfStockException("Order " + command.orderId() + " has an item out of stock");
            }
            events.publish(new StockReservedEvent(command.orderId(), command.customerId(), command.totalCents()));
        }

        @EventHandler
        @ProcessingGroup("replenishment")
        public void replenish(StockReservedEvent event) {
            awaitLatency(2, 6);
            if (chance(15)) {
                commands.getObject().send(new ReorderStockCommand(event.orderId()));
            }
        }

        @CommandHandler
        public void reorder(ReorderStockCommand command) {
            awaitLatency(40, 120);
            events.publish(new StockReorderedEvent(command.orderId()));
        }

        // Reads the cancelled orders from the topic storefront appends them to.
        @EventHandler
        @ProcessingGroup("stock-release")
        public void release(OrderCancelledEvent event) {
            commands.getObject().send(new ReleaseStockCommand(event.orderId()));
        }

        @CommandHandler
        public void release(ReleaseStockCommand command) {
            awaitLatency(3, 8);
            events.publish(new StockReleasedEvent(command.orderId()));
        }
    }

    public record ReserveStockCommand(long orderId, long customerId, long totalCents) implements Command<Void> {
    }

    public record ReleaseStockCommand(long orderId) implements Command<Void> {
    }

    public record ReorderStockCommand(long orderId) implements Command<Void> {
    }

    public record StockReservedEvent(long orderId, long customerId, long totalCents) implements Event {
    }

    public record StockReleasedEvent(long orderId) implements Event {
    }

    public record StockReorderedEvent(long orderId) implements Event {
    }

    public static class OutOfStockException extends RuntimeException {

        public OutOfStockException(String message) {
            super(message);
        }
    }
}
