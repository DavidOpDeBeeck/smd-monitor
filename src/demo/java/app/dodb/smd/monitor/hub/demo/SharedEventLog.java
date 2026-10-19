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

import java.util.concurrent.LinkedBlockingQueue;

// An in-memory log one application appends its events to and a processing group of another application reads, as applications sharing an
// event store do: the group handles the events of the other application without them being published again.
public class SharedEventLog implements EventMedium {

    public static final SharedEventLog TICKETS = new SharedEventLog();

    private static final Logger LOGGER = LoggerFactory.getLogger(SharedEventLog.class);
    private static final EventDelivery DELIVERY = new EventDelivery("shared-log");

    private final LinkedBlockingQueue<EventMessage<?>> messages = new LinkedBlockingQueue<>(10_000);
    private final EventSink inlet = new Inlet();
    private final EventSource outlet = new Outlet();

    @Override
    public EventSink inlet() {
        return inlet;
    }

    @Override
    public EventSource outlet() {
        return outlet;
    }

    private class Inlet implements EventSink {

        @Override
        public <E extends Event> void send(EventMessage<E> eventMessage) {
            messages.offer(eventMessage);
        }

        @Override
        public EventDelivery delivery() {
            return DELIVERY;
        }
    }

    // One reader for its one subscriber, on a virtual thread of its own until the subscription is closed.
    private class Outlet implements EventSource {

        @Override
        public EventSubscription subscribe(EventSubscriber subscriber) {
            var reader = Thread.ofVirtual().name("shared-log-" + subscriber.processingGroup()).start(() -> read(subscriber));
            return reader::interrupt;
        }

        @Override
        public EventDelivery delivery() {
            return DELIVERY;
        }

        private void read(EventSubscriber subscriber) {
            while (!Thread.currentThread().isInterrupted()) {
                EventMessage<?> message;
                try {
                    message = messages.take();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                try {
                    MetadataFactory.runInScope(message, () -> subscriber.on(message));
                } catch (RuntimeException failure) {
                    LOGGER.warn("Shared log handling failed: processingGroup={}", subscriber.processingGroup(), failure);
                }
            }
        }
    }
}
