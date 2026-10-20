package app.dodb.smd.monitor.hub.otlp;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

import static java.util.Objects.requireNonNull;

// The OTLP port serves the endpoint only. The page's port serves nothing under /v1/, a margin around the endpoint for any path Spring MVC would
// match to it that differs from Tomcat's normalized servlet path.
class OtlpPortFilter extends HttpFilter {

    private static final String OTLP_PATHS = "/v1/";
    private final OtlpConnector connector;

    OtlpPortFilter(OtlpConnector connector) {
        this.connector = requireNonNull(connector);
    }

    @Override
    protected void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException, ServletException {
        var path = request.getServletPath();
        var served = connector.received(request) ? path.equals(OtlpTraceController.PATH) : !path.startsWith(OTLP_PATHS);
        if (!served) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        chain.doFilter(request, response);
    }
}
