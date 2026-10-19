package app.dodb.smd.monitor.hub.demo;

import app.dodb.smd.monitor.hub.demo.inventory.Inventory;
import app.dodb.smd.monitor.hub.demo.payments.Payments;
import app.dodb.smd.monitor.hub.demo.shipping.Shipping;
import app.dodb.smd.monitor.hub.demo.storefront.Storefront;
import app.dodb.smd.monitor.hub.spring.MonitorHubApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

// A web shop: storefront takes orders on two instances, inventory reserves their stock, payments charges them, and shipping ships them; each
// application appends its events to its own topic, which the processing groups of the next one read.
public class HubDemo {

    // Arguments starting with an application's name, such as --storefront.smd.demo.orders-per-second=50 or
    // --payments.management.opentelemetry.tracing.export.schedule-delay=8s, configure that application; the others configure the hub.
    private static final List<String> APPLICATIONS = List.of("storefront", "inventory", "payments", "shipping");

    public static void main(String[] args) {
        var hub = new SpringApplicationBuilder(MonitorHubApplication.class)
            .properties("server.address=127.0.0.1", "server.port=8080", "spring.application.name=smd-monitor-hub",
                "management.tracing.sampling.probability=0.0")
            .run(Arrays.stream(args).filter(arg -> APPLICATIONS.stream().noneMatch(application -> arg.startsWith(prefix(application)))).toArray(String[]::new));
        var traces = "http://127.0.0.1:" + hub.getEnvironment().getProperty("local.server.port") + "/v1/traces";
        // The readers subscribe before the applications that write to their topics start.
        start(Inventory.class, "inventory", "inventory-1", traces, args);
        start(Payments.class, "payments", "payments-1", traces, args);
        start(Shipping.class, "shipping", "shipping-1", traces, args);
        start(Storefront.class, "storefront", "storefront-1", traces, args);
        start(Storefront.class, "storefront", "storefront-2", traces, args);
    }

    private static void start(Class<?> service, String application, String host, String traces, String[] args) {
        var prefix = prefix(application);
        var serviceArgs = Arrays.stream(args).filter(arg -> arg.startsWith(prefix)).map(arg -> "--" + arg.substring(prefix.length()));
        new SpringApplicationBuilder(service)
            .web(WebApplicationType.NONE)
            .properties("spring.application.name=" + application, "management.opentelemetry.resource-attributes.host.name=" + host,
                "management.tracing.sampling.probability=1.0", "management.opentelemetry.tracing.export.otlp.endpoint=" + traces,
                "management.opentelemetry.tracing.export.schedule-delay=1s", "spring.task.scheduling.pool.size=4", "spring.main.banner-mode=off")
            // One JVM holds the hub and every application, and only one may register the admin MBean an IDE enables through a system property; a
            // command-line argument outranks it.
            .run(Stream.concat(Stream.of("--spring.application.admin.enabled=false"), serviceArgs).toArray(String[]::new));
    }

    private static String prefix(String application) {
        return "--" + application + ".";
    }

    public static void awaitLatency(long minMillis, long maxMillis) {
        awaitLatency(ThreadLocalRandom.current().nextLong(minMillis, maxMillis + 1));
    }

    public static void awaitLatency(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Demo interrupted", failure);
        }
    }

    public static boolean chance(int percent) {
        return ThreadLocalRandom.current().nextInt(100) < percent;
    }
}
