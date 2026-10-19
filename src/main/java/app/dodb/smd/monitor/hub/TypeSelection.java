package app.dodb.smd.monitor.hub;

import app.dodb.smd.monitor.hub.span.MessageKind;
import app.dodb.smd.monitor.hub.specimen.Specimen;

import java.util.Set;

import static java.util.Objects.requireNonNull;

// Every type when it names neither kinds nor types; otherwise the types of the kinds and the types it names.
public record TypeSelection(Set<MessageKind> kinds, Set<ApplicationType> types) {

    public static final TypeSelection EVERY_TYPE = new TypeSelection(Set.of(), Set.of());

    public TypeSelection {
        requireNonNull(kinds);
        requireNonNull(types);
    }

    public boolean matches(Specimen specimen) {
        return kinds.isEmpty() && types.isEmpty() || kinds.contains(specimen.type().kind()) || types.contains(specimen.applicationType());
    }

    public boolean everyType() {
        return kinds.isEmpty() && types.isEmpty();
    }
}
