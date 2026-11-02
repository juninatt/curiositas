package se.pbt.curiositas.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import se.pbt.curiositas.person.PersonController;
import se.pbt.curiositas.person.PersonService;
import se.pbt.curiositas.person.StoredPersons;
import se.pbt.curiositas.web.DatasetETag;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;

/**
 * Verifies that anyone can read but only the admin can write, since the database is public and
 * its content must not be changed by strangers.
 */
@WebMvcTest(PersonController.class)
@Import({SecurityConfig.class, DatasetETag.class})
@TestPropertySource(properties = "curiositas.admin.password=test-password")
class WriteSecurityTest {

    private static final String PERSON = "/api/v1/persons/7f1c8a52-0d1e-4f4b-9a7e-3c2b1d0e9f8a";
    private static final String BODY = "{\"name\": \"Edward Teach\", \"gender\": \"MALE\"}";

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private PersonService service;

    /** Lets anyone read without logging in. */
    @Test
    void allowsReadingWithoutCredentials() {
        given(service.list(0, 20)).willReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        assertThat(mvc.get().uri("/api/v1/persons")).hasStatusOk();
    }

    /**
     * Answers 401 as Problem Details, with a challenge naming the Basic scheme, when a write is
     * made without credentials.
     */
    @ParameterizedTest
    @EnumSource(value = WriteRequest.class)
    void rejectsWritesWithoutCredentials(WriteRequest write) {
        assertThat(mvc.method(write.method).uri(write.uri).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .hasStatus(HttpStatus.UNAUTHORIZED)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .hasHeader(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"Curiositas\"");
    }

    /** Answers 401 when the password is wrong, without revealing whether the username exists. */
    @ParameterizedTest
    @EnumSource(value = WriteRequest.class)
    void rejectsWritesWithWrongPassword(WriteRequest write) {
        assertThat(mvc.method(write.method).uri(write.uri)
                .with(httpBasic("admin", "wrong-password"))
                .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .hasStatus(HttpStatus.UNAUTHORIZED)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
    }

    /** Lets the admin through to the controller, which answers as it would for any valid write. */
    @ParameterizedTest
    @EnumSource(value = WriteRequest.class)
    void letsAdminWrite(WriteRequest write) {
        given(service.create(any())).willAnswer(invocation ->
                StoredPersons.stored(invocation.getArgument(0), UUID.randomUUID(), 0));

        assertThat(mvc.method(write.method).uri(write.uri)
                .with(httpBasic("admin", "test-password"))
                .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .hasStatus(write.statusForAdmin);
    }

    /** The write operations in the contract, each with its method, path and answer for the admin. */
    enum WriteRequest {
        CREATE(HttpMethod.POST, "/api/v1/persons", HttpStatus.CREATED),
        // No If-Match is sent, so getting 428 instead of 401 shows the request passed authentication.
        REPLACE(HttpMethod.PUT, PERSON, HttpStatus.PRECONDITION_REQUIRED),
        DELETE(HttpMethod.DELETE, PERSON, HttpStatus.NO_CONTENT);

        private final HttpMethod method;
        private final String uri;
        private final HttpStatus statusForAdmin;

        /** Pairs an HTTP method with the path it is sent to and the status the admin gets. */
        WriteRequest(HttpMethod method, String uri, HttpStatus statusForAdmin) {
            this.method = method;
            this.uri = uri;
            this.statusForAdmin = statusForAdmin;
        }
    }
}
