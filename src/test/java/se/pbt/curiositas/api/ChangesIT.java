package se.pbt.curiositas.api;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;

/**
 * Verifies the change feed from end to end, as a client keeping a copy uses it: load the snapshot,
 * then ask for the changes after its version, with every exchange checked against the contract.
 */
class ChangesIT extends ApiTestBase {

    /**
     * Lists the changes made after a snapshot in order, each person exactly as the person endpoint
     * showed it, and a deletion by id only.
     */
    @Test
    void listsChangesAfterSnapshot() {
        long version = snapshotVersion();
        String id = admin().body("{ \"name\": \"Edward Teach\", \"gender\": \"MALE\" }")
                .when().post("/persons")
                .then().statusCode(201)
                .extract().path("id");
        Map<String, Object> afterReplace = admin().header("If-Match", "\"0\"")
                .body("{ \"name\": \"Edward Thatch\", \"gender\": \"MALE\" }")
                .when().put("/persons/{id}", id)
                .then().statusCode(200)
                .extract().jsonPath().getMap("$");
        admin().when().delete("/persons/{id}", id)
                .then().statusCode(204);

        Map<String, Object> replaced = client().queryParam("after", version)
                .when().get("/changes")
                .then().statusCode(200)
                .body("changes.change", contains("CREATED", "UPDATED", "DELETED"))
                .body("changes.recordType", contains("PERSON", "PERSON", "PERSON"))
                .body("changes.id", contains(id, id, id))
                .body("changes[2]", not(hasKey("person")))
                .body("version", equalTo(Math.toIntExact(snapshotVersion())))
                .body("hasMore", equalTo(false))
                .extract().jsonPath().getMap("changes[1].person");

        assertThat(replaced).isEqualTo(afterReplace);
    }

    /** Lets a client page through the changes, each page continuing where the last one stopped. */
    @Test
    void pagesThroughChanges() {
        long version = snapshotVersion();
        for (String name : new String[] {"Edward Teach", "Anne Bonny"}) {
            admin().body("{ \"name\": \"" + name + "\", \"gender\": \"UNKNOWN\" }")
                    .when().post("/persons")
                    .then().statusCode(201);
        }

        int next = client().queryParam("after", version).queryParam("limit", 1)
                .when().get("/changes")
                .then().statusCode(200)
                .body("changes.person.name", contains("Edward Teach"))
                .body("hasMore", equalTo(true))
                .extract().path("version");
        client().queryParam("after", next).queryParam("limit", 1)
                .when().get("/changes")
                .then().statusCode(200)
                .body("changes.person.name", contains("Anne Bonny"))
                .body("hasMore", equalTo(false));
        client().queryParam("after", snapshotVersion())
                .when().get("/changes")
                .then().statusCode(200)
                .body("changes", empty());
    }

    /** Answers 400 for a version beyond the latest, telling the client to load the snapshot again. */
    @Test
    void answersBadRequestForVersionAhead() {
        client().queryParam("after", snapshotVersion() + 1)
                .when().get("/changes")
                .then().statusCode(400)
                .contentType("application/problem+json")
                .body("detail", containsString("/api/v1/snapshot"));
    }

    /** Returns the version of the dataset, from the snapshot. */
    private long snapshotVersion() {
        return ((Number) client().when().get("/snapshot")
                .then().statusCode(200)
                .extract().path("version")).longValue();
    }
}
