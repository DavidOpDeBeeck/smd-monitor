package app.dodb.smd.monitor.hub.web;

import app.dodb.smd.monitor.hub.Epoch;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

// The answers of the latest epoch asked about, by query (W5), so the queries of every page for the same epoch are answered once, and at most
// MAX_RUNNING of them computed at once (C9), beyond which a query is answered 429. A query is keyed by its normalized form, the parsed query,
// which compares by value. The first to ask computes the answer while those asking the same wait for it; other queries never wait. It keeps at
// most 256 answers, and forgets them when a later epoch is asked about; a 429 is not kept.
final class QueryAnswers {

    static final int MAX_ANSWERS = 256;
    // As many as the design target's 16 viewers ask at once when each looks at another view; fewer refused every viewer but four most
    // seconds in the viewer ladder of docs/monitor-hub-design.md, at no cost the ladder could tell.
    static final int MAX_RUNNING = 16;

    private final Semaphore running;
    private final QueryAnswer busy;
    private volatile Answers answers = new Answers(-1, null);

    QueryAnswers() {
        this(MAX_RUNNING);
    }

    // A benchmark tunes how many run at once.
    QueryAnswers(int maxRunning) {
        if (maxRunning < 1) {
            throw new IllegalArgumentException("QueryAnswers runs at least one query at once; got " + maxRunning);
        }
        this.running = new Semaphore(maxRunning);
        this.busy = QueryAnswer.busy("The SMD monitor hub answers at most " + maxRunning + " queries at once; ask again shortly");
    }

    // The answer of the query for the epoch, computed by the supplier unless known.
    QueryAnswer answer(Epoch epoch, Object query, Supplier<QueryAnswer> compute) {
        requireNonNull(query);
        var current = answersOf(epoch);
        var mine = new CompletableFuture<QueryAnswer>();
        var known = current.byQuery.size() >= MAX_ANSWERS ? current.byQuery.get(query) : current.byQuery.putIfAbsent(query, mine);
        if (known != null) {
            return known.join();
        }
        try {
            var answer = running.tryAcquire() ? computeReleasing(compute) : busy;
            if (answer == busy) {
                current.byQuery.remove(query, mine);
            }
            mine.complete(answer);
            return answer;
        } catch (RuntimeException failure) {
            current.byQuery.remove(query, mine);
            mine.completeExceptionally(failure);
            throw failure;
        }
    }

    private QueryAnswer computeReleasing(Supplier<QueryAnswer> compute) {
        try {
            return compute.get();
        } finally {
            running.release();
        }
    }

    private Answers answersOf(Epoch epoch) {
        var current = answers;
        if (current.epoch == epoch.sequence() && epoch.session().equals(current.session)) {
            return current;
        }
        synchronized (this) {
            if (answers.epoch != epoch.sequence() || !epoch.session().equals(answers.session)) {
                answers = new Answers(epoch.sequence(), epoch.session());
            }
            return answers;
        }
    }

    private static final class Answers {

        private final long epoch;
        private final UUID session;
        private final ConcurrentHashMap<Object, CompletableFuture<QueryAnswer>> byQuery = new ConcurrentHashMap<>();

        private Answers(long epoch, UUID session) {
            this.epoch = epoch;
            this.session = session;
        }
    }
}
