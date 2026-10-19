package app.dodb.smd.monitor.hub;

import static java.util.Objects.requireNonNull;

// The latest epoch the writer published. Readers take it without waiting for the writer, and the frame publisher waits here for the next one;
// publishing never waits for a reader.
final class Epochs {

    private final Object published = new Object();
    private volatile Epoch latest;

    Epochs(Epoch first) {
        this.latest = requireNonNull(first);
    }

    Epoch latest() {
        return latest;
    }

    void publish(Epoch epoch) {
        synchronized (published) {
            latest = epoch;
            published.notifyAll();
        }
    }

    Epoch awaitAfter(long sequence) throws InterruptedException {
        synchronized (published) {
            while (latest.sequence() <= sequence) {
                published.wait();
            }
            return latest;
        }
    }
}
