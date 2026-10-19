package app.dodb.smd.monitor.hub.span;

import static java.util.Objects.requireNonNull;

public sealed interface Stage {

    MessageType type();

    // A command's or query's dispatch covers its handler too.
    record Dispatch(MessageType type) implements Stage {

        public Dispatch {
            requireNonNull(type);
        }
    }

    // Each processing group handles an event; commands and queries have no handling of their own.
    record Handling(MessageType type, String processingGroup) implements Stage {

        public Handling {
            requireNonNull(type);
            requireNonNull(processingGroup);
            if (type.kind() != MessageKind.EVENT) {
                throw new IllegalArgumentException("Handling in processing group '" + processingGroup + "' requires an event; got " + type.kind() + " "
                    + type.name() + ", whose dispatch covers its handler");
            }
        }
    }
}
