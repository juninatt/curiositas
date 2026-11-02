package se.pbt.curiositas.api;

import io.restassured.path.json.JsonPath;
import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the promise of the dataset endpoints from end to end: a client that loads the snapshot
 * once and then follows the change feed always ends up with exactly the data on the server, no
 * matter when it syncs, how many changes it missed, or how they are paged. Every exchange is
 * checked against the contract.
 *
 * <p>The changes are chosen at random from a fixed seed, so a failure can be repeated exactly.
 */
class SyncIT extends ApiTestBase {

    private static final long SEED = 20261102L;

    private final AtomicInteger names = new AtomicInteger();

    /**
     * Syncs at random moments while persons are created, replaced and deleted, and compares the
     * client's copy with the snapshot after every sync.
     */
    @Test
    void clientSyncingAtRandomMomentsAlwaysMatchesServer() {
        Random random = new Random(SEED);
        Writer writer = new Writer(random);
        SyncingClient client = new SyncingClient();
        client.loadSnapshot();

        for (int step = 0; step < 150; step++) {
            if (random.nextInt(4) > 0) {
                writer.changeSomething();
            } else {
                client.sync(1 + random.nextInt(5));
                assertMatchesServer(client, "step " + step);
            }
        }
        client.sync(100);
        assertMatchesServer(client, "the end");
    }

    /**
     * Replays the whole change feed from version 0 into exactly the snapshot, deleted persons
     * included, which shows that the feed alone holds the full dataset.
     */
    @Test
    void replayingFeedFromStartGivesSnapshot() {
        Writer writer = new Writer(new Random(SEED));
        for (int i = 0; i < 60; i++) {
            writer.changeSomething();
        }

        SyncingClient client = new SyncingClient();
        client.sync(7);

        assertMatchesServer(client, "replayed from version 0");
    }

    /**
     * Keeps syncing while several writers change persons at the same time, never sees the version
     * go back, and ends up identical to the server once they stop.
     *
     * <p>A missed change needs a sync to land within milliseconds of two concurrent commits, which
     * this test rarely hits; {@code PersonWriteTurnsTest} proves the order of commits directly.
     */
    @Test
    void clientSyncingDuringConcurrentWritesEndsUpMatchingServer() throws Exception {
        SyncingClient client = new SyncingClient();
        client.loadSnapshot();
        ExecutorService writers = Executors.newFixedThreadPool(3);
        try {
            List<Future<?>> running = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                Writer writer = new Writer(new Random(SEED + i));
                running.add(writers.submit(() -> {
                    for (int change = 0; change < 20; change++) {
                        writer.changeSomething();
                    }
                }));
            }

            long lastVersion = client.version;
            while (!running.stream().allMatch(Future::isDone)) {
                client.sync(3);
                assertThat(client.version).as("the version never goes back").isGreaterThanOrEqualTo(lastVersion);
                lastVersion = client.version;
            }
            for (Future<?> writer : running) {
                writer.get();
            }
        } finally {
            writers.shutdownNow();
        }

        client.sync(100);
        assertMatchesServer(client, "after the writers stopped");
    }

    /** Checks that the client holds exactly the persons, and the version, of the snapshot. */
    private void assertMatchesServer(SyncingClient client, String when) {
        JsonPath snapshot = client().when().get("/snapshot")
                .then().statusCode(200)
                .extract().jsonPath();
        Map<String, Map<String, Object>> server = new HashMap<>();
        for (Map<String, Object> person : snapshot.<Map<String, Object>>getList("persons")) {
            server.put((String) person.get("id"), person);
        }

        assertThat(client.persons).as("persons at %s, seed %d", when, SEED).isEqualTo(server);
        assertThat(client.version).as("version at %s, seed %d", when, SEED).isEqualTo(snapshot.getLong("version"));
    }

    /**
     * A client that keeps a copy of the dataset, the way the README describes it: load the
     * snapshot, then apply the changes after its version, page by page.
     */
    private final class SyncingClient {

        private final Map<String, Map<String, Object>> persons = new HashMap<>();
        private long version;

        /** Replaces the copy with the snapshot and its version. */
        void loadSnapshot() {
            JsonPath snapshot = client().when().get("/snapshot")
                    .then().statusCode(200)
                    .extract().jsonPath();
            persons.clear();
            for (Map<String, Object> person : snapshot.<Map<String, Object>>getList("persons")) {
                persons.put((String) person.get("id"), person);
            }
            version = snapshot.getLong("version");
        }

        /** Applies every change after the copy's version, asking for at most {@code limit} at a time. */
        @SuppressWarnings("unchecked")
        void sync(int limit) {
            boolean hasMore;
            do {
                JsonPath page = client().queryParam("after", version).queryParam("limit", limit)
                        .when().get("/changes")
                        .then().statusCode(200)
                        .extract().jsonPath();
                for (Map<String, Object> change : page.<Map<String, Object>>getList("changes")) {
                    String id = (String) change.get("id");
                    if ("DELETED".equals(change.get("change"))) {
                        persons.remove(id);
                    } else {
                        persons.put(id, (Map<String, Object>) change.get("person"));
                    }
                }
                version = page.getLong("version");
                hasMore = page.getBoolean("hasMore");
            } while (hasMore);
        }
    }

    /**
     * Changes persons through the API at random: creates one, replaces one or deletes one. It
     * keeps the ETag of each person it created, to send in {@code If-Match}.
     */
    private final class Writer {

        private final Random random;
        private final Map<String, String> eTags = new HashMap<>();

        Writer(Random random) {
            this.random = random;
        }

        /** Makes one random change; creates a person when there is none to change. */
        void changeSomething() {
            int choice = eTags.isEmpty() ? 0 : random.nextInt(3);
            if (choice == 0) {
                ExtractableResponse<Response> created = admin().body(newPerson())
                        .when().post("/persons")
                        .then().statusCode(201)
                        .extract();
                eTags.put(created.path("id"), created.header("ETag"));
                return;
            }
            List<String> ids = new ArrayList<>(eTags.keySet());
            ids.sort(null);
            String id = ids.get(random.nextInt(ids.size()));
            if (choice == 1) {
                String eTag = admin().header("If-Match", eTags.get(id))
                        .body(newPerson())
                        .when().put("/persons/{id}", id)
                        .then().statusCode(200)
                        .extract().header("ETag");
                eTags.put(id, eTag);
            } else {
                admin().when().delete("/persons/{id}", id)
                        .then().statusCode(204);
                eTags.remove(id);
            }
        }

        /** Returns a person with a name never used before, so it is never seen as a duplicate. */
        private String newPerson() {
            return "{ \"name\": \"Person " + names.incrementAndGet() + "\", \"birth\": { \"year\": "
                    + (1600 + random.nextInt(200)) + " }, \"gender\": \"UNKNOWN\" }";
        }
    }
}
