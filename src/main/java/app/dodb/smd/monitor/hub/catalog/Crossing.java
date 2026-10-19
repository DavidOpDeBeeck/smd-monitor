package app.dodb.smd.monitor.hub.catalog;

import app.dodb.smd.monitor.hub.span.ApplicationName;

import static java.util.Objects.requireNonNull;

// A link into this application from a parent in another one.
public record Crossing(ApplicationName parentApplication, CausalLink link) {

    public Crossing {
        requireNonNull(parentApplication);
        requireNonNull(link);
    }
}
