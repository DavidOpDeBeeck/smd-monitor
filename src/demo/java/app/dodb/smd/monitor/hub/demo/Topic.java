package app.dodb.smd.monitor.hub.demo;

import app.dodb.smd.api.event.Event;
import app.dodb.smd.api.event.EventMessage;
import app.dodb.smd.api.event.delivery.EventDelivery;
import app.dodb.smd.api.event.delivery.EventMedium;
import app.dodb.smd.api.event.delivery.EventSink;
import app.dodb.smd.api.event.delivery.EventSource;
import app.dodb.smd.api.event.delivery.EventSubscriber;
import app.dodb.smd.api.event.delivery.EventSubscription;
import app.dodb.smd.api.metadata.MetadataFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

// An in-memory topic of a message broker: one application appends all its events to it, and every processing group subscribed to it, in any
// application, receives each event once. The instances of an application subscribe the same group and share its events, as a consumer group
// does. The events keep their message ID and metadata, so the groups continue the trace of their publication.
public class Topic implements EventMedium {

    public static final Topic ORDERS = new Topic("orders-topic");
    public static final Topic STOCK = new Topic("stock-topic");
    public static final Topic PAYMENTS = new Topic("payments-topic");
    public static final Topic SHIPMENTS = new Topic("shipments-topic");

    private static final Logger LOGGER = LoggerFactory.getLogger(Topic.class);

    private final EventDelivery delivery;
    private final ConcurrentHashMap<String, LinkedBlockingQueue<EventMessage<?>>> groups = new ConcurrentHashMap<>();
    private final EventSink inlet = new Inlet();
    private final EventSource outlet = new Outlet();

    private Topic(String name) {
        this.delivery = new EventDelivery(name);
    }

    @Override
    public EventSink inlet() {
        return inlet;
    }

    @Override
    public EventSource outlet() {
        return outlet;
    }

    private LinkedBlockingQueue<EventMessage<?>> queue(String processingGroup) {
        return groups.computeIfAbsent(processingGroup, group -> new LinkedBlockingQueue<>(10_000));
    }

    // A group that has not subscribed yet misses the events appended before it does.
    private class Inlet implements EventSink {

        @Override
        public <E extends Event> void send(EventMessage<E> eventMessage) {
            groups.values().forEach(queue -> queue.offer(eventMessage));
        }

        @Override
        public EventDelivery delivery() {
            return delivery;
        }
    }

    // One reader per subscriber, on a virtual thread of its own until the subscription is closed.
    private class Outlet implements EventSource {

        @Override
        public EventSubscription subscribe(EventSubscriber subscriber) {
            var queue = queue(subscriber.processingGroup());
            var reader = Thread.ofVirtual().name(delivery.name() + "-" + subscriber.processingGroup()).start(() -> read(queue, subscriber));
            return reader::interrupt;
        }

        @Override
        public EventDelivery delivery() {
            return delivery;
        }

        private void read(LinkedBlockingQueue<EventMessage<?>> queue, EventSubscriber subscriber) {
            while (!Thread.currentThread().isInterrupted()) {
                EventMessage<?> message;
                try {
                    message = queue.take();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                try {
                    MetadataFactory.runInScope(message, () -> subscriber.on(message));
                } catch (RuntimeException failure) {
                    // The demo fails on purpose; the monitor shows each failure.
                    LOGGER.debug("Topic handling failed: topic={}, processingGroup={}", delivery.name(), subscriber.processingGroup(), failure);
                }
            }
        }
    }
}
