package app.dodb.smd.monitor.hub.demo.storefront;

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
import app.dodb.smd.monitor.hub.demo.payments.Payments.PaymentDeclinedEvent;
import app.dodb.smd.monitor.hub.demo.shipping.Shipping.ShipmentScheduledEvent;
import app.dodb.smd.spring.EnableSMD;
import app.dodb.smd.spring.EventBusSpecCustomizer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.LongStream;

import static app.dodb.smd.monitor.hub.demo.HubDemo.awaitLatency;

@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration
@EnableScheduling
@EnableConfigurationProperties(StorefrontProperties.class)
@EnableSMD(packages = "app.dodb.smd.monitor.hub.demo.storefront")
@Import(Storefront.StorefrontHandlers.class)
public class Storefront {

    private static final List<String> SEARCHES = List.of("running shoes", "rain jacket", "headphones", "coffee grinder", "desk lamp", "backpack");

    @Bean
    public EventBusSpecCustomizer ordersTopic() {
        return spec -> spec.sinks(Topic.ORDERS.inlet());
    }

    @Bean
    public ProcessingGroupsConfigurer storefrontProcessingGroups() {
        return spec -> spec
            .processingGroup("order-history").sync()
            .processingGroup("customer-emails").source(Topic.SHIPMENTS.outlet())
            .processingGroup("payment-follow-up").source(Topic.PAYMENTS.outlet());
    }

    public record StorefrontHandlers(EventPublisher events, ObjectProvider<CommandGateway> commands, ObjectProvider<QueryGateway> queries,
                                     StorefrontProperties properties) {

        @Scheduled(fixedDelay = 350, initialDelay = 1000)
        public void browse() {
            var random = ThreadLocalRandom.current();
            var products = queries.getObject().send(new SearchProductsQuery(SEARCHES.get(random.nextInt(SEARCHES.size()))));
            queries.getObject().send(new GetProductQuery(products.get(random.nextInt(products.size()))));
        }

        @Scheduled(fixedDelay = 1500, initialDelay = 1500)
        public void checkOut() {
            var random = ThreadLocalRandom.current();
            commands.getObject().send(new PlaceOrderCommand(random.nextLong(100_000, 1_000_000), random.nextLong(1, 5_000), random.nextInt(1, 6)));
        }

        @Scheduled(fixedRate = 1000, initialDelay = 1000)
        public void checkOutInBulk() {
            if (properties.ordersPerSecond() == 0) {
                return;
            }
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                for (var order = 0; order < properties.ordersPerSecond(); order++) {
                    executor.submit(this::checkOut);
                }
            }
        }

        @QueryHandler
        public List<Long> search(SearchProductsQuery query) {
            awaitLatency(15, 45);
            return LongStream.rangeClosed(1, 12).map(rank -> Math.abs(query.text().hashCode() % 1_000) * 100 + rank).boxed().toList();
        }

        @QueryHandler
        public String find(GetProductQuery query) {
            awaitLatency(2, 6);
            return "Product " + query.productId();
        }

        @CommandHandler
        public long place(PlaceOrderCommand command) {
            var total = queries.getObject().send(new PriceCartQuery(command.customerId(), command.items()));
            awaitLatency(5, 12);
            events.publish(new OrderPlacedEvent(command.orderId(), command.customerId(), total));
            return command.orderId();
        }

        @QueryHandler
        public long price(PriceCartQuery query) {
            awaitLatency(4, 10);
            return query.items() * ThreadLocalRandom.current().nextLong(1_500, 12_000);
        }

        @CommandHandler
        public void cancel(CancelOrderCommand command) {
            awaitLatency(4, 10);
            events.publish(new OrderCancelledEvent(command.orderId(), command.reason()));
        }

        @EventHandler
        @ProcessingGroup("order-history")
        public void record(OrderPlacedEvent event) {
            awaitLatency(1, 4);
        }

        @EventHandler
        @ProcessingGroup("order-history")
        public void record(OrderCancelledEvent event) {
            awaitLatency(1, 4);
        }

        // Reads the declined payments from the topic payments appends them to, and cancels their orders, which releases their stock.
        @EventHandler
        @ProcessingGroup("payment-follow-up")
        public void cancelOrder(PaymentDeclinedEvent event) {
            commands.getObject().send(new CancelOrderCommand(event.orderId(), event.reason()));
        }

        @EventHandler
        @ProcessingGroup("customer-emails")
        public void emailShipment(ShipmentScheduledEvent event) {
            awaitLatency(20, 60);
        }
    }

    public record SearchProductsQuery(String text) implements Query<List<Long>> {
    }

    public record GetProductQuery(long productId) implements Query<String> {
    }

    public record PriceCartQuery(long customerId, int items) implements Query<Long> {
    }

    public record PlaceOrderCommand(long orderId, long customerId, int items) implements Command<Long> {
    }

    public record CancelOrderCommand(long orderId, String reason) implements Command<Void> {
    }

    public record OrderPlacedEvent(long orderId, long customerId, long totalCents) implements Event {
    }

    public record OrderCancelledEvent(long orderId, String reason) implements Event {
    }
}
