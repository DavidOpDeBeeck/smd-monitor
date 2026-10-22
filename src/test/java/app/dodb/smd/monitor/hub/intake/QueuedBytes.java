package app.dodb.smd.monitor.hub.intake;

// The room a batch takes in the intake queues, for tests that size the queues by the batches they offer.
public final class QueuedBytes {

    private QueuedBytes() {
    }

    public static long of(SpanBatch batch) {
        return QueuedBatch.of(batch).bytes();
    }
}
