package app.dodb.smd.monitor.hub;

import app.dodb.smd.monitor.hub.span.ApplicationName;
import app.dodb.smd.monitor.hub.span.Outcome;
import app.dodb.smd.monitor.hub.specimen.Specimen;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;

// Which retained invocations a page shows: those of the scope, of any of the applications, types, and statuses named, and holding the search.
public record InvocationFilter(Optional<ApplicationName> scope, Set<ApplicationName> applications, TypeSelection types, Set<Outcome.Status> statuses,
                               String search) {

    public static final InvocationFilter EVERY_INVOCATION = new InvocationFilter(Optional.empty(), Set.of(), TypeSelection.EVERY_TYPE, Set.of(), "");

    public InvocationFilter {
        requireNonNull(scope);
        requireNonNull(applications);
        requireNonNull(types);
        requireNonNull(statuses);
        requireNonNull(search);
        search = search.strip().toLowerCase(Locale.ROOT);
    }

    public boolean inScope(Specimen specimen) {
        return scope.isEmpty() || scope.get().equals(specimen.application());
    }

    public boolean matches(Specimen specimen) {
        return inScope(specimen)
            && (applications.isEmpty() || applications.contains(specimen.application()))
            && (statuses.isEmpty() || statuses.contains(specimen.status()))
            && types.matches(specimen)
            && found(specimen, search);
    }

    // Separated by spaces, so a search may span neighbouring fields; the search is stripped and lowercase.
    static boolean found(Specimen specimen, String search) {
        if (search.isEmpty()) {
            return true;
        }
        var text = String.join(" ", specimen.type().name(), specimen.processingGroup().orElse(""), specimen.messageId().value().toString(),
            specimen.instance().value(), specimen.threadName(), specimen.failureType().orElse(""));
        return text.toLowerCase(Locale.ROOT).contains(search);
    }
}
