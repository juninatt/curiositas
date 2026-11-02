package se.pbt.curiositas.api;

import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;

/**
 * Verifies the person endpoints from end to end: over real HTTP, through authentication, the
 * service and the database, and back, with every exchange checked against the contract. The
 * controller tests check each detail of the HTTP layer; these tests prove the parts work together.
 */
class PersonLifecycleIT extends ApiTestBase {

    private static final String TEACH = """
            {
              "name": "Edward Teach",
              "wikidataId": "Q213518",
              "alsoKnownAs": [{ "name": "Blackbeard", "type": "NICKNAME" }],
              "birth": { "year": 1680, "uncertaintyYears": 5 },
              "death": { "year": 1718, "month": 11, "day": 22 },
              "birthPlace": "probably Bristol",
              "deathPlace": "Ocracoke Island",
              "birthCountry": "GB",
              "gender": "MALE"
            }""";

    /** Follows one person through its whole life in the API: created, read, listed, replaced and deleted. */
    @Test
    void createsReadsListsReplacesAndDeletesPerson() {
        ExtractableResponse<Response> created = admin().body(TEACH)
                .when().post("/persons")
                .then().statusCode(201)
                .header("ETag", "\"0\"")
                .body("name", equalTo("Edward Teach"))
                .body("alsoKnownAs[0].name", equalTo("Blackbeard"))
                .body("birth.display", equalTo("1680 ± 5"))
                .body("death.display", equalTo("22 Nov 1718"))
                .body("ageAtDeath.min", equalTo(32))
                .body("ageAtDeath.max", equalTo(43))
                .body("birthCountry.name", equalTo("United Kingdom"))
                .extract();
        String id = created.path("id");
        assertThat(created.header("Location")).endsWith("/api/v1/persons/" + id);

        client().when().get("/persons/{id}", id)
                .then().statusCode(200)
                .header("ETag", "\"0\"")
                .body("name", equalTo("Edward Teach"))
                .body("birthPlace", equalTo("probably Bristol"));

        client().when().get("/persons")
                .then().statusCode(200)
                .body("content.id", contains(id))
                .body("totalElements", equalTo(1));

        admin().header("If-Match", "\"0\"")
                .body(TEACH.replace("\"Edward Teach\"", "\"Edward Thatch\""))
                .when().put("/persons/{id}", id)
                .then().statusCode(200)
                .header("ETag", "\"1\"")
                .body("name", equalTo("Edward Thatch"))
                .body("createdAt", equalTo(created.path("createdAt")))
                .body("updatedAt", not(equalTo(created.path("updatedAt"))));

        client().when().get("/persons/{id}", id)
                .then().statusCode(200)
                .header("ETag", "\"1\"")
                .body("name", equalTo("Edward Thatch"));

        admin().when().delete("/persons/{id}", id)
                .then().statusCode(204);

        client().when().get("/persons/{id}", id)
                .then().statusCode(404);
        client().when().get("/persons")
                .then().statusCode(200)
                .body("content", empty())
                .body("totalElements", equalTo(0));
    }

    /** Stores a person with only the required fields and leaves every unknown value out of the response. */
    @Test
    void storesPersonWithOnlyRequiredFields() {
        String id = admin().body("""
                        { "name": "Anne Bonny", "gender": "FEMALE" }""")
                .when().post("/persons")
                .then().statusCode(201)
                .extract().path("id");

        client().when().get("/persons/{id}", id)
                .then().statusCode(200)
                .body("name", equalTo("Anne Bonny"))
                .body("alsoKnownAs", empty())
                .body("$", not(hasKey("birth")))
                .body("$", not(hasKey("wikidataId")))
                .body("$", not(hasKey("ageAtDeath")));
    }

    /** Lists persons ordered by name, one page at a time, with the totals a client needs to page through them. */
    @Test
    void listsPersonsByNameOnePageAtATime() {
        for (String name : new String[] {"Julius Caesar", "Edward Teach", "Anne Bonny"}) {
            admin().body("{ \"name\": \"" + name + "\", \"gender\": \"UNKNOWN\" }")
                    .when().post("/persons")
                    .then().statusCode(201);
        }

        client().queryParam("page", 0).queryParam("size", 2)
                .when().get("/persons")
                .then().statusCode(200)
                .body("content.name", contains("Anne Bonny", "Edward Teach"))
                .body("page", equalTo(0))
                .body("size", equalTo(2))
                .body("totalElements", equalTo(3))
                .body("totalPages", equalTo(2));

        client().queryParam("page", 1).queryParam("size", 2)
                .when().get("/persons")
                .then().statusCode(200)
                .body("content.name", contains("Julius Caesar"));
    }
}
