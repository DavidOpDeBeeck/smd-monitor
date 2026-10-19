package app.dodb.smd.monitor.hub;

public record InvocationId(long value) {

    public InvocationId {
        if (value < 1) {
            throw new IllegalArgumentException("InvocationId requires a positive value; got " + value);
        }
    }
}
