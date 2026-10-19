package app.dodb.smd.monitor.hub.join;

import static java.util.Objects.requireNonNull;

// A child linked to its parent, found when the second of them arrived. When both dispatch the same message, another application delivered it.
public record Link(Fact child, Fact parent, boolean delivered) {

    public Link {
        requireNonNull(child);
        requireNonNull(parent);
    }

    public boolean local() {
        return parent.application().equals(child.application());
    }
}
