package se.pbt.curiositas.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

/**
 * Verifies from end to end that unchanged data is never sent twice: a client that sends back the
 * ETag it got gets 304 Not Modified without a body, until something changes. Every exchange is
 * checked against the contract.
 */
class NotModifiedIT extends ApiTestBase {

    /** Answers 304 for a person until it changes, then sends it again with its new version. */
    @Test
    void sendsPersonAgainOnlyAfterItChanges() {
        String id = create("Edward Teach");

        client().header("If-None-Match", "\"0\"")
                .when().get("/persons/{id}", id)
                .then().statusCode(304)
                .header("ETag", "\"0\"")
                .body(emptyString());

        admin().header("If-Match", "\"0\"")
                .body("{ \"name\": \"Edward Thatch\", \"gender\": \"MALE\" }")
                .when().put("/persons/{id}", id)
                .then().statusCode(200);

        client().header("If-None-Match", "\"0\"")
                .when().get("/persons/{id}", id)
                .then().statusCode(200)
                .header("ETag", "\"1\"")
                .body("name", equalTo("Edward Thatch"));
    }

    /** Answers 304 for a page of persons until anything in the dataset changes. */
    @Test
    void sendsPageAgainOnlyAfterTheDatasetChanges() {
        create("Edward Teach");
        String eTag = client().when().get("/persons")
                .then().statusCode(200)
                .extract().header("ETag");

        client().header("If-None-Match", eTag)
                .when().get("/persons")
                .then().statusCode(304)
                .header("ETag", eTag)
                .body(emptyString());

        create("Anne Bonny");

        client().header("If-None-Match", eTag)
                .when().get("/persons")
                .then().statusCode(200)
                .header("ETag", not(equalTo(eTag)))
                .body("totalElements", equalTo(2));
    }

    /**
     * Tags the snapshot with its own version and the release, and answers 304 until the dataset
     * changes, also for a weak tag, which If-None-Match compares by value.
     */
    @Test
    void sendsSnapshotAgainOnlyAfterTheDatasetChanges() {
        create("Edward Teach");
        String eTag = client().when().get("/snapshot")
                .then().statusCode(200)
                .extract().header("ETag");
        long version = ((Number) client().when().get("/snapshot").then().extract().path("version")).longValue();

        assertThat(eTag).startsWith("\"" + version + "-").endsWith("\"");
        client().header("If-None-Match", eTag)
                .when().get("/snapshot")
                .then().statusCode(304)
                .body(emptyString());
        client().header("If-None-Match", "W/" + eTag)
                .when().get("/snapshot")
                .then().statusCode(304);

        create("Anne Bonny");

        client().header("If-None-Match", eTag)
                .when().get("/snapshot")
                .then().statusCode(200)
                .body("persons.size()", equalTo(2));
    }

    /** Creates a person as the admin and returns its id. */
    private String create(String name) {
        return admin().body("{ \"name\": \"" + name + "\", \"gender\": \"UNKNOWN\" }")
                .when().post("/persons")
                .then().statusCode(201)
                .extract().path("id");
    }
}
