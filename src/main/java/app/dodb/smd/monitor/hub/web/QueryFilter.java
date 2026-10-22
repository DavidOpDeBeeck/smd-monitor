package app.dodb.smd.monitor.hub.web;

import java.util.List;

import static java.util.Objects.requireNonNull;

// The invocations a query of the page selects, as its parameters name them: the scope and the search are null when absent.
public record QueryFilter(String scope, List<String> applications, List<String> kinds, List<String> types, List<String> statuses, String search) {

    public QueryFilter {
        requireNonNull(applications);
        requireNonNull(kinds);
        requireNonNull(types);
        requireNonNull(statuses);
    }
}
