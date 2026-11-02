package se.pbt.curiositas.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static org.hamcrest.Matchers.equalTo;

/**
 * Verifies that the API answers sensibly whatever a client puts in the {@code Accept} header:
 * data as {@code application/json}, errors as {@code application/problem+json}, and 406 Not
 * Acceptable when neither can be sent. Clients and HTTP libraries send many different headers,
 * and none of the common ones may stop a client from using the API.
 */
class ContentNegotiationIT extends ApiTestBase {

    private static final String PROBLEM_JSON = "application/problem+json";

    /** Sends data as JSON for every common Accept header, never labelled as an error. */
    @ParameterizedTest
    @ValueSource(strings = {"*/*", "application/json", "application/json, application/problem+json",
            "application/problem+json"})
    void sendsDataAsJson(String accept) {
        client().accept(accept)
                .when().get("/persons")
                .then().statusCode(200)
                .contentType("application/json");
    }

    /** Sends errors as Problem Details also to clients that only ask for JSON. */
    @ParameterizedTest
    @ValueSource(strings = {"*/*", "application/json", "application/problem+json"})
    void sendsErrorsAsProblemDetails(String accept) {
        client().accept(accept)
                .when().get("/persons/{id}", UUID.randomUUID())
                .then().statusCode(404)
                .contentType(PROBLEM_JSON);
    }

    /**
     * Creates, replaces and deletes a person for a client that only accepts JSON, as many HTTP
     * libraries are set up to. Deleting answers without a body, so what the client accepts must
     * not matter. The contract only lists Problem Details for the errors of a delete, so the
     * request counts as breaking the contract and only the response is checked.
     */
    @Test
    void writesForClientAcceptingOnlyJson() {
        String id = admin().accept("application/json")
                .body("{ \"name\": \"Anne Bonny\", \"gender\": \"FEMALE\" }")
                .when().post("/persons")
                .then().statusCode(201)
                .contentType("application/json")
                .extract().path("id");

        admin().accept("application/json").header("If-Match", "\"0\"")
                .body("{ \"name\": \"Ann Bonny\", \"gender\": \"FEMALE\" }")
                .when().put("/persons/{id}", id)
                .then().statusCode(200)
                .contentType("application/json");

        adminBreakingContract().accept("application/json")
                .when().delete("/persons/{id}", id)
                .then().statusCode(204);
        client().when().get("/persons/{id}", id)
                .then().statusCode(404);
    }

    /** Answers 406 as Problem Details when the client accepts neither JSON nor Problem Details. */
    @ParameterizedTest
    @ValueSource(strings = {"text/html", "application/xml"})
    void answersNotAcceptableForOtherMediaTypes(String accept) {
        clientBreakingContract().accept(accept)
                .when().get("/persons")
                .then().statusCode(406)
                .contentType(PROBLEM_JSON)
                .body("status", equalTo(406));
    }

    /** Checks credentials before the media type, so a write without them is always told to log in. */
    @Test
    void answersUnauthorizedBeforeNotAcceptable() {
        clientBreakingContract().accept("text/html")
                .when().delete("/persons/{id}", UUID.randomUUID())
                .then().statusCode(401)
                .contentType(PROBLEM_JSON);
    }
}
