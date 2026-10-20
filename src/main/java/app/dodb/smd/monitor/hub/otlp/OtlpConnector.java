package app.dodb.smd.monitor.hub.otlp;

import jakarta.servlet.ServletRequest;
import org.apache.catalina.connector.Connector;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

import static java.util.Objects.requireNonNull;

// The port the applications export to, apart from the page's (C11): a proxy publishes the page without the endpoint, and the connector has its
// own Tomcat threads, so requests of the page never take a thread from admission.
class OtlpConnector implements WebServerFactoryCustomizer<TomcatServletWebServerFactory>, ApplicationListener<WebServerInitializedEvent> {

    static final String LOCAL_PORT = "local.smd.monitor.hub.otlp.port";
    private final Connector connector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
    private final OtlpProperties properties;

    OtlpConnector(OtlpProperties properties) {
        this.properties = requireNonNull(properties);
    }

    @Override
    public void customize(TomcatServletWebServerFactory factory) {
        if (properties.port() != 0 && properties.port() == factory.getPort()) {
            throw new IllegalStateException("smd.monitor.hub.otlp.port must differ from server.port, which serves the page; both are " + factory.getPort());
        }
        connector.setPort(properties.port());
        properties.listenAddress().ifPresent(address -> connector.setProperty("address", address.getHostAddress()));
        factory.addAdditionalConnectors(connector);
    }

    boolean received(ServletRequest request) {
        return request.getLocalPort() == connector.getLocalPort();
    }

    // Names the port bound, as Spring Boot names the page's in local.server.port, for a hub started on port 0.
    @Override
    public void onApplicationEvent(WebServerInitializedEvent event) {
        var environment = (ConfigurableEnvironment) event.getApplicationContext().getEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource(LOCAL_PORT, Map.of(LOCAL_PORT, connector.getLocalPort())));
    }
}
