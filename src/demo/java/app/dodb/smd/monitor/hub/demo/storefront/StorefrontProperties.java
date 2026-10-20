package app.dodb.smd.monitor.hub.demo.storefront;

import org.springframework.boot.context.properties.ConfigurationProperties;

// --storefront.smd.demo.orders-per-second=N places N more orders every second on each instance, in parallel, so a hub with a small memory budget
// has to let go of traces.
@ConfigurationProperties(prefix = "smd.demo")
public record StorefrontProperties(int ordersPerSecond) {

    public StorefrontProperties {
        if (ordersPerSecond < 0) {
            throw new IllegalArgumentException("smd.demo.orders-per-second must be 0 or more; got " + ordersPerSecond);
        }
    }
}
