package se.pbt.curiositas.api;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;

/**
 * Verifies the snapshot from end to end: every person as the person endpoints show them, at the
 * version of the latest change, with every exchange checked against the contract.
 */
class SnapshotIT extends ApiTestBase {

    /** Returns every person by name, in the same form as reading each one, at the latest version. */
    @Test
    void returnsWholeDatasetAtLatestVersion() {
        String teach = create("""
                { "name": "Edward Teach", "birth": { "year": 1680, "uncertaintyYears": 5 },
                  "death": { "year": 1718, "month": 11, "day": 22 }, "gender": "MALE" }""");
        String bonny = create("{ \"name\": \"Anne Bonny\", \"gender\": \"FEMALE\" }");

        long version = ((Number) client().when().get("/snapshot")
                .then().statusCode(200)
                .body("persons.name", contains("Anne Bonny", "Edward Teach"))
                .body("persons[1].id", equalTo(teach))
                .body("persons[1].ageAtDeath.max", equalTo(43))
                .extract().path("version")).longValue();

        assertThat(version).isEqualTo(latestSequenceNumberOf(bonny));
        Map<String, Object> teachAlone = client().when().get("/persons/{id}", teach)
                .then().statusCode(200)
                .extract().jsonPath().getMap("$");
        Map<String, Object> teachInSnapshot = client().when().get("/snapshot")
                .then().statusCode(200)
                .extract().jsonPath().getMap("persons[1]");
        assertThat(teachInSnapshot).isEqualTo(teachAlone);
    }

    /** Creates a person as the admin and returns its id. */
    private String create(String json) {
        return admin().body(json)
                .when().post("/persons")
                .then().statusCode(201)
                .extract().path("id");
    }

    /** Returns the sequence number of the person's latest change, read from its revisions. */
    private long latestSequenceNumberOf(String id) {
        List<Number> numbers = client().when().get("/persons/{id}/revisions", id)
                .then().statusCode(200)
                .extract().path("revisions.sequenceNumber");
        return numbers.getLast().longValue();
    }
}
