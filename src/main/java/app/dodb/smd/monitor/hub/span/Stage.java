package app.dodb.smd.monitor.hub.span;

import java.util.Optional;

import static java.util.Objects.requireNonNull;

public sealed interface Stage {

    MessageType type();

    record Dispatch(MessageType type) implements Stage {

        public Dispatch {
            requireNonNull(type);
        }
    }

    // Each processing group handles an event; a command or query has a single handler and no processing group.
    record Handling(MessageType type, Optional<String> processingGroup) implements Stage {

        public Handling {
            requireNonNull(type);
            requireNonNull(processingGroup);
            if (processingGroup.isPresent() != (type.kind() == MessageKind.EVENT)) {
                throw new IllegalArgumentException("Handling of " + type.kind() + " " + type.name() + " requires a processing group for an event and none for "
                    + "a command or query; got " + processingGroup.map(group -> "processing group '" + group + "'").orElse("no processing group"));
            }
        }
    }
}
