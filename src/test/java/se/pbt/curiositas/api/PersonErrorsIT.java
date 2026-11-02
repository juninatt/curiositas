package se.pbt.curiositas.api;

import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 * Verifies every error response from end to end, as a client meets it: the status, the Problem
 * Details body (RFC 9457) and that the data is left unchanged. Each response is checked against
 * the contract, so clients can rely on errors having the documented shape.
 */
class PersonErrorsIT extends ApiTestBase {

    private static final String TEACH = """
            { "name": "Edward Teach", "wikidataId": "Q213518", "gender": "MALE" }""";

    private static final String PROBLEM_JSON = "application/problem+json";

    /** Answers 400 when the body is not JSON at all, and stores nothing. */
    @Test
    void answersBadRequestForMalformedJson() {
        adminBreakingContract().body("{ \"name\": ")
                .when().post("/persons")
                .then().statusCode(400)
                .contentType(PROBLEM_JSON)
                .body("status", equalTo(400));

        assertNumberOfPersons(0);
    }

    /** Answers 400 when the id in the path is not a UUID. */
    @Test
    void answersBadRequestForMalformedId() {
        clientBreakingContract().when().get("/persons/{id}", "not-a-uuid")
                .then().statusCode(400)
                .contentType(PROBLEM_JSON)
                .body("instance", equalTo("/api/v1/persons/not-a-uuid"));
    }

    /** Answers 400 for paging outside the limits in the contract, naming the parameter. */
    @Test
    void answersBadRequestForInvalidPaging() {
        clientBreakingContract().queryParam("size", 101)
                .when().get("/persons")
                .then().statusCode(400)
                .contentType(PROBLEM_JSON)
                .body("detail", containsString("size"));
    }

    /** Answers 401 with an authentication challenge for every write without credentials, and changes nothing. */
    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "DELETE"})
    void answersUnauthorizedForWriteWithoutCredentials(String method) {
        String id = createTeach();

        send(clientBreakingContract().header("If-Match", "\"0\""), method, id)
                .then().statusCode(401)
                .contentType(PROBLEM_JSON)
                .header("WWW-Authenticate", "Basic realm=\"Curiositas\"");

        assertUnchanged(id);
    }

    /** Answers 401 for a write with the wrong password, and changes nothing. */
    @Test
    void answersUnauthorizedForWrongPassword() {
        String id = createTeach();

        client().auth().preemptive().basic("admin", "wrong-password")
                .when().delete("/persons/{id}", id)
                .then().statusCode(401)
                .contentType(PROBLEM_JSON);

        assertUnchanged(id);
    }

    /** Answers 404 for every operation on a person that has never existed. */
    @ParameterizedTest
    @ValueSource(strings = {"GET", "PUT", "DELETE"})
    void answersNotFoundForUnknownPerson(String method) {
        String unknownId = UUID.randomUUID().toString();

        send(admin().header("If-Match", "\"0\""), method, unknownId)
                .then().statusCode(404)
                .contentType(PROBLEM_JSON)
                .body("status", equalTo(404))
                .body("detail", containsString(unknownId));
    }

    /**
     * Answers 410 for every operation on a deleted person, pointing to its history, so a client
     * knows the id was valid and can remove its own copy.
     */
    @ParameterizedTest
    @ValueSource(strings = {"GET", "PUT", "DELETE"})
    void answersGoneForDeletedPerson(String method) {
        String id = createTeach();
        admin().when().delete("/persons/{id}", id)
                .then().statusCode(204);

        send(admin().header("If-Match", "\"0\""), method, id)
                .then().statusCode(410)
                .contentType(PROBLEM_JSON)
                .body("status", equalTo(410))
                .body("detail", containsString("/api/v1/persons/" + id + "/revisions"));

        assertNumberOfPersons(0);
    }

    /** Answers 409 naming the existing person when the same person is created twice, and stores only one. */
    @Test
    void answersConflictForDuplicatePerson() {
        String id = createTeach();

        admin().body(TEACH)
                .when().post("/persons")
                .then().statusCode(409)
                .contentType(PROBLEM_JSON)
                .body("detail", containsString(id));

        assertNumberOfPersons(1);
    }

    /** Answers 412 when the person was changed after the client read it, and keeps the other change. */
    @Test
    void answersPreconditionFailedForOutdatedVersion() {
        String id = createTeach();
        replace(id, "\"0\"", "Edward Thatch").then().statusCode(200);

        replace(id, "\"0\"", "Edward Drummond")
                .then().statusCode(412)
                .contentType(PROBLEM_JSON)
                .body("detail", containsString("version 1"));

        client().when().get("/persons/{id}", id)
                .then().body("name", equalTo("Edward Thatch"));
    }

    /** Answers 412 for If-Match values that cannot name a version, such as weak ETags and "*". */
    @ParameterizedTest
    @ValueSource(strings = {"W/\"0\"", "*"})
    void answersPreconditionFailedForUnusableIfMatch(String ifMatch) {
        String id = createTeach();

        replace(id, ifMatch, "Edward Thatch")
                .then().statusCode(412)
                .contentType(PROBLEM_JSON);

        assertUnchanged(id);
    }

    /** Answers 422 listing every field that breaks the schema, so the client can fix them all at once. */
    @Test
    void answersUnprocessableForBodyThatBreaksTheSchema() {
        adminBreakingContract().body("""
                        { "birth": { "year": 1680, "month": 13 }, "birthCountry": "gb", "gender": "MALE" }""")
                .when().post("/persons")
                .then().statusCode(422)
                .contentType(PROBLEM_JSON)
                .body("errors.field", contains("birth.month", "birthCountry", "name"));

        assertNumberOfPersons(0);
    }

    /** Answers 422 naming the field when a value is not one of the allowed ones. */
    @Test
    void answersUnprocessableForUnknownEnumValue() {
        adminBreakingContract().body("""
                        { "name": "Edward Teach", "gender": "PIRATE" }""")
                .when().post("/persons")
                .then().statusCode(422)
                .contentType(PROBLEM_JSON)
                .body("errors.field", contains("gender"));
    }

    /** Answers 422 for data the schema allows but that cannot be true, such as 31 April. */
    @Test
    void answersUnprocessableForImpossibleDate() {
        admin().body("""
                        { "name": "Edward Teach", "birth": { "year": 1718, "month": 4, "day": 31 }, "gender": "MALE" }""")
                .when().post("/persons")
                .then().statusCode(422)
                .contentType(PROBLEM_JSON)
                .body("errors.field", contains("birth"));

        assertNumberOfPersons(0);
    }

    /** Answers 422 when a replacement would make the death come before the birth, and changes nothing. */
    @Test
    void answersUnprocessableForDeathBeforeBirth() {
        String id = createTeach();

        admin().header("If-Match", "\"0\"")
                .body("""
                        { "name": "Edward Teach", "birth": { "year": 1718 }, "death": { "year": 1680 }, "gender": "MALE" }""")
                .when().put("/persons/{id}", id)
                .then().statusCode(422)
                .contentType(PROBLEM_JSON)
                .body("errors.field", contains("death"));

        assertUnchanged(id);
    }

    /** Answers 428 when a replacement lacks If-Match, since the client must show which version it read. */
    @Test
    void answersPreconditionRequiredWithoutIfMatch() {
        String id = createTeach();

        admin().body(TEACH)
                .when().put("/persons/{id}", id)
                .then().statusCode(428)
                .contentType(PROBLEM_JSON);

        assertUnchanged(id);
    }

    /** Creates Edward Teach and returns his id. */
    private String createTeach() {
        return admin().body(TEACH)
                .when().post("/persons")
                .then().statusCode(201)
                .extract().path("id");
    }

    /** Replaces the person's name, sending the given If-Match header. */
    private Response replace(String id, String ifMatch, String name) {
        return admin().header("If-Match", ifMatch)
                .body("{ \"name\": \"" + name + "\", \"gender\": \"MALE\" }")
                .when().put("/persons/{id}", id);
    }

    /** Sends a request with the given method to the person, or to the collection for POST. */
    private static Response send(RequestSpecification request, String method, String id) {
        return switch (method) {
            case "GET" -> request.when().get("/persons/{id}", id);
            case "POST" -> request.body(TEACH).when().post("/persons");
            case "PUT" -> request.body(TEACH).when().put("/persons/{id}", id);
            case "DELETE" -> request.when().delete("/persons/{id}", id);
            default -> throw new IllegalArgumentException("Unexpected method " + method);
        };
    }

    /** Checks that the person is still stored exactly as created by {@link #createTeach()}. */
    private void assertUnchanged(String id) {
        client().when().get("/persons/{id}", id)
                .then().statusCode(200)
                .header("ETag", "\"0\"")
                .body("name", equalTo("Edward Teach"));
    }

    /** Checks how many persons are stored. */
    private void assertNumberOfPersons(int expected) {
        client().when().get("/persons")
                .then().statusCode(200)
                .body("totalElements", equalTo(expected));
    }
}
