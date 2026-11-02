package se.pbt.curiositas.api;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.report.LevelResolver;
import com.atlassian.oai.validator.report.ValidationReport;
import com.atlassian.oai.validator.restassured.OpenApiValidationFilter;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import se.pbt.curiositas.TestcontainersConfiguration;
import se.pbt.curiositas.person.PersonRepository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Base for API tests that call the running application over real HTTP, the way any client does,
 * with a real database behind it. Every response is checked against the OpenAPI contract, so a
 * difference between the code and the contract fails the test that caused it.
 *
 * <p>Requests are checked too, which proves that the tests use the API as the contract describes
 * it. Tests of error responses send requests the contract forbids on purpose; they use the
 * {@code ...BreakingContract} methods, which check only the response.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "curiositas.admin.password=" + ApiTestBase.ADMIN_PASSWORD)
@Import(TestcontainersConfiguration.class)
abstract class ApiTestBase {

    /** The admin password the application is started with. */
    static final String ADMIN_PASSWORD = "test-password";

    /** Checks both the request and the response against the contract. */
    private static final OpenApiValidationFilter WHOLE_EXCHANGE =
            contractFilter(LevelResolver.create().build());

    /** Checks only the response, for requests that break the contract on purpose. */
    private static final OpenApiValidationFilter RESPONSE_ONLY = contractFilter(LevelResolver.create()
            .withLevel("validation.request", ValidationReport.Level.IGNORE)
            .build());

    @LocalServerPort
    private int port;

    @Autowired
    private PersonRepository persons;

    /** Starts every test from an empty database, so tests never depend on each other. */
    @BeforeEach
    void startFromEmptyDatabase() {
        persons.deleteAll();
    }

    /**
     * Starts a request without credentials, as a reading client sends it.
     *
     * @return the request, with paths relative to the API's base path
     */
    RequestSpecification client() {
        return request(WHOLE_EXCHANGE);
    }

    /**
     * Starts a request with the admin's credentials, as needed for writes.
     *
     * @return the request, with paths relative to the API's base path
     */
    RequestSpecification admin() {
        return asAdmin(request(WHOLE_EXCHANGE));
    }

    /**
     * Starts a request without credentials that breaks the contract on purpose.
     *
     * @return the request, of which only the response is checked against the contract
     */
    RequestSpecification clientBreakingContract() {
        return request(RESPONSE_ONLY);
    }

    /**
     * Starts a request with the admin's credentials that breaks the contract on purpose.
     *
     * @return the request, of which only the response is checked against the contract
     */
    RequestSpecification adminBreakingContract() {
        return asAdmin(request(RESPONSE_ONLY));
    }

    /** Creates a JSON request to this test's server, checked by the given filter. */
    private RequestSpecification request(OpenApiValidationFilter contract) {
        return RestAssured.given()
                .port(port)
                .basePath("/api/v1")
                .contentType(ContentType.JSON)
                .filter(contract);
    }

    /** Adds the admin's credentials, sent with the first request instead of after a challenge. */
    private static RequestSpecification asAdmin(RequestSpecification request) {
        return request.auth().preemptive().basic("admin", ADMIN_PASSWORD);
    }

    /** Creates a filter that fails the test when the exchange differs from the contract. */
    private static OpenApiValidationFilter contractFilter(LevelResolver levels) {
        return new OpenApiValidationFilter(OpenApiInteractionValidator
                .createForInlineApiSpecification(readContract())
                .withLevelResolver(levels)
                .build());
    }

    /** Reads the contract the server code is generated from. */
    private static String readContract() {
        try {
            return new ClassPathResource("openapi.yaml").getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the OpenAPI contract", e);
        }
    }
}
