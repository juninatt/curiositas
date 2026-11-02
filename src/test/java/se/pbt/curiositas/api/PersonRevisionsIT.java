package se.pbt.curiositas.api;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;

/**
 * Verifies the history of a person from end to end: changes made over HTTP show up as revisions
 * that anyone can read, also after the person is deleted, with every exchange checked against
 * the contract.
 */
class PersonRevisionsIT extends ApiTestBase {

    /** Lists every change to a person, oldest first, with the person as it was after each one. */
    @Test
    void listsEveryChangeToPerson() {
        String id = admin().body("""
                        { "name": "Edward Teach", "birth": { "year": 1680, "uncertaintyYears": 5 }, "gender": "MALE" }""")
                .when().post("/persons")
                .then().statusCode(201)
                .extract().path("id");
        admin().header("If-Match", "\"0\"")
                .body("{ \"name\": \"Edward Thatch\", \"gender\": \"MALE\" }")
                .when().put("/persons/{id}", id)
                .then().statusCode(200);

        List<Long> sequenceNumbers = client().when().get("/persons/{id}/revisions", id)
                .then().statusCode(200)
                .body("personId", equalTo(id))
                .body("revisions.revision", contains(1, 2))
                .body("revisions.change", contains("CREATED", "UPDATED"))
                .body("revisions.source", everyItem(equalTo("API")))
                .body("revisions[0].person.name", equalTo("Edward Teach"))
                .body("revisions[0].person.birth.uncertaintyYears", equalTo(5))
                .body("revisions[1].person.name", equalTo("Edward Thatch"))
                .body("revisions[1].person", not(hasKey("birth")))
                .extract().jsonPath().getList("revisions.sequenceNumber", Long.class);

        assertThat(sequenceNumbers).isSorted().doesNotHaveDuplicates();
    }

    /** Keeps the history of a deleted person, ending with the person as it was before the deletion. */
    @Test
    void keepsHistoryOfDeletedPerson() {
        String id = admin().body("{ \"name\": \"Anne Bonny\", \"gender\": \"FEMALE\" }")
                .when().post("/persons")
                .then().statusCode(201)
                .extract().path("id");
        admin().when().delete("/persons/{id}", id)
                .then().statusCode(204);

        client().when().get("/persons/{id}/revisions", id)
                .then().statusCode(200)
                .body("revisions.change", contains("CREATED", "DELETED"))
                .body("revisions[1].person.name", equalTo("Anne Bonny"));
    }

    /** Answers 404 for a person that has never existed. */
    @Test
    void answersNotFoundForUnknownPerson() {
        client().when().get("/persons/{id}/revisions", UUID.randomUUID())
                .then().statusCode(404)
                .contentType("application/problem+json");
    }
}
