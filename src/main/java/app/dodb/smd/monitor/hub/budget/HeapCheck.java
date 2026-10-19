package app.dodb.smd.monitor.hub.budget;

import static java.util.Objects.requireNonNull;

// How the hub's memory budget compares to the JVM's maximum heap at startup.
public sealed interface HeapCheck {

    record Fits() implements HeapCheck {
    }

    record Warns(String message) implements HeapCheck {

        public Warns {
            requireNonNull(message);
        }
    }

    record Exceeds(String message) implements HeapCheck {

        public Exceeds {
            requireNonNull(message);
        }
    }
}
