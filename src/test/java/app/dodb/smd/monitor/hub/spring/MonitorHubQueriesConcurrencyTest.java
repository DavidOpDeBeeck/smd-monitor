package app.dodb.smd.monitor.hub.spring;

import app.dodb.smd.monitor.hub.HubConfig;
import app.dodb.smd.monitor.hub.MonitorHub;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;

class MonitorHubQueriesConcurrencyTest {

    private final MonitorHub hub = new MonitorHub(HubConfig.withDefaults().build());
    private final MonitorHubQueries queries = new MonitorHubQueries(hub);
    private final CountDownLatch release = new CountDownLatch(1);
    private final ArrayList<Thread> running = new ArrayList<>();

    @AfterEach
    void release() throws InterruptedException {
        release.countDown();
        for (var thread : running) {
            thread.join();
        }
    }

    @Test
    void tape_whileAsManyQueriesRunAsAllowed_answersTooManyRequests() throws InterruptedException {
        runBlockedQueries(MonitorHubQueries.MAX_RUNNING);

        var response = tape();

        assertThat(response.getStatusCode().value()).isEqualTo(429);
        assertThat(response.getBody()).contains("at most 4 queries");
    }

    @Test
    void tape_whileFewerQueriesRunThanAllowed_answersFromTheLatestEpoch() throws InterruptedException {
        runBlockedQueries(MonitorHubQueries.MAX_RUNNING - 1);

        var response = tape();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains(hub.epoch().session().toString());
    }

    private void runBlockedQueries(int count) throws InterruptedException {
        var started = new CountDownLatch(count);
        for (var index = 0; index < count; index++) {
            var query = List.of("blocked", index);
            running.add(Thread.ofPlatform().start(() -> queries.answer(query, epoch -> {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                return ResponseEntity.ok(epoch.session().toString());
            })));
        }
        started.await();
    }

    private ResponseEntity<String> tape() {
        return queries.tape(Optional.empty(), null, null, null, null, "", 0, Optional.empty(), 100);
    }
}
