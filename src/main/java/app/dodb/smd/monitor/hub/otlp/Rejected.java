package app.dodb.smd.monitor.hub.otlp;

// The SMD spans of one export the hub could not read or refused, and the first reason, which answers the export.
final class Rejected {

    private long count;
    private String first;

    void add(long spans, String reason) {
        count += spans;
        if (first == null) {
            first = reason;
        }
    }

    long count() {
        return count;
    }

    String first() {
        return first;
    }
}
