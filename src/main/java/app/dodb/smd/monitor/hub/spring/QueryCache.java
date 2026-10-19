package app.dodb.smd.monitor.hub.spring;

import app.dodb.smd.monitor.hub.Epoch;
import org.springframework.http.ResponseEntity;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

// The answers of the latest epoch asked about, by query (W5), so the queries of every page for the same epoch are answered once. A query is
// keyed by its normalized form, the parsed query, which compares by value. The first to ask computes the answer while those asking the same wait
// for it; other queries never wait. It keeps at most 256 answers, and forgets them when a later epoch is asked about; an answer that could not be
// computed, as too many queries ran at once, is not kept.
final class QueryCache {

    static final int MAX_ANSWERS = 256;

    private volatile Answers answers = new Answers(-1, null);

    // The answer of the query for the epoch, computed by the supplier unless known; null when the supplier answered null.
    ResponseEntity<String> answer(Epoch epoch, Object query, Supplier<ResponseEntity<String>> compute) {
        requireNonNull(query);
        var current = answersOf(epoch);
        var mine = new CompletableFuture<ResponseEntity<String>>();
        var known = current.byQuery.size() >= MAX_ANSWERS ? current.byQuery.get(query) : current.byQuery.putIfAbsent(query, mine);
        if (known != null) {
            return known.join();
        }
        try {
            var answer = compute.get();
            if (answer == null) {
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
        private final ConcurrentHashMap<Object, CompletableFuture<ResponseEntity<String>>> byQuery = new ConcurrentHashMap<>();

        private Answers(long epoch, UUID session) {
            this.epoch = epoch;
            this.session = session;
        }
    }
}
