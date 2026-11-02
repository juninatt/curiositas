package se.pbt.curiositas.person;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import se.pbt.curiositas.security.SecurityConfig;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.LIST;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static se.pbt.curiositas.person.StoredPersons.stored;
import static se.pbt.curiositas.person.StoredPersons.storedRevision;

/**
 * Verifies the person endpoints at the HTTP level: paths, status codes, headers, JSON and error
 * responses. The service is replaced by a mock, so these tests run without a database.
 */
@WebMvcTest(PersonController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = "curiositas.admin.password=test-password")
class PersonControllerTest {

    private static final UUID ID = UUID.fromString("7f1c8a52-0d1e-4f4b-9a7e-3c2b1d0e9f8a");

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private PersonService service;

    /** Returns the person as JSON with its version as ETag, which later guards replacements. */
    @Test
    void returnsPersonWithETag() {
        given(service.get(ID)).willReturn(stored(new Person("Edward Teach", Gender.MALE), ID, 3));

        assertThat(mvc.get().uri("/api/v1/persons/{id}", ID))
                .hasStatusOk()
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .hasHeader("ETag", "\"3\"")
                .bodyJson()
                .hasPathSatisfying("$.id", id -> assertThat(id).asString().isEqualTo(ID.toString()))
                .hasPathSatisfying("$.name", name -> assertThat(name).asString().isEqualTo("Edward Teach"))
                .hasPathSatisfying("$.gender", gender -> assertThat(gender).asString().isEqualTo("MALE"));
    }

    /** Answers 304 without a body when the client already has the current version of the person. */
    @Test
    void answersNotModifiedForCurrentVersion() {
        given(service.get(ID)).willReturn(stored(new Person("Edward Teach", Gender.MALE), ID, 3));

        assertThat(mvc.get().uri("/api/v1/persons/{id}", ID).header("If-None-Match", "\"3\""))
                .hasStatus(HttpStatus.NOT_MODIFIED)
                .hasHeader("ETag", "\"3\"")
                .body().isEmpty();
        assertThat(mvc.get().uri("/api/v1/persons/{id}", ID).header("If-None-Match", "\"2\""))
                .hasStatusOk();
    }

    /** Leaves unknown values out of the JSON instead of sending null, as the contract promises. */
    @Test
    void leavesUnknownValuesOutOfJson() {
        given(service.get(ID)).willReturn(stored(new Person("Edward Teach", Gender.MALE), ID, 0));

        assertThat(mvc.get().uri("/api/v1/persons/{id}", ID))
                .hasStatusOk()
                .bodyJson()
                .doesNotHavePath("$.birth")
                .doesNotHavePath("$.birthCountry")
                .doesNotHavePath("$.wikidataId")
                .doesNotHavePath("$.ageAtDeath");
    }

    /** Answers 404 as Problem Details when the person does not exist. */
    @Test
    void answersNotFoundForUnknownPerson() {
        given(service.get(ID)).willThrow(new PersonNotFoundException(ID));

        assertThat(mvc.get().uri("/api/v1/persons/{id}", ID))
                .hasStatus(HttpStatus.NOT_FOUND)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .hasPathSatisfying("$.status", status -> assertThat(status).asNumber().isEqualTo(404))
                .hasPathSatisfying("$.detail", detail -> assertThat(detail).asString().contains(ID.toString()));
    }

    /** Answers 410 as Problem Details when the person has been deleted, pointing to its history. */
    @Test
    void answersGoneForDeletedPerson() {
        given(service.get(ID)).willThrow(new PersonDeletedException(ID, StoredPersons.STORED_AT));

        assertThat(mvc.get().uri("/api/v1/persons/{id}", ID))
                .hasStatus(HttpStatus.GONE)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .hasPathSatisfying("$.status", status -> assertThat(status).asNumber().isEqualTo(410))
                .hasPathSatisfying("$.detail", detail -> assertThat(detail).asString()
                        .contains("/api/v1/persons/" + ID + "/revisions"));
    }

    /** Answers 400 as Problem Details when the id is not a UUID. */
    @Test
    void answersBadRequestForMalformedId() {
        assertThat(mvc.get().uri("/api/v1/persons/not-a-uuid"))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
    }

    /** Returns every revision of a person, oldest first, each with the person as it then was. */
    @Test
    void listsRevisionsOfPerson() {
        Person teach = stored(new Person("Edward Teach", Gender.MALE), ID, 0);
        Person thatch = stored(new Person("Edward Thatch", Gender.MALE), ID, 1);
        given(service.revisions(ID)).willReturn(List.of(
                storedRevision(41, teach, 1, ChangeType.CREATED),
                storedRevision(57, thatch, 2, ChangeType.UPDATED)));

        assertThat(mvc.get().uri("/api/v1/persons/{id}/revisions", ID))
                .hasStatusOk()
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .bodyJson()
                .hasPathSatisfying("$.personId", id -> assertThat(id).asString().isEqualTo(ID.toString()))
                .hasPathSatisfying("$.revisions[*].sequenceNumber", numbers -> assertThat(numbers).asInstanceOf(LIST)
                        .containsExactly(41, 57))
                .hasPathSatisfying("$.revisions[1].revision", revision -> assertThat(revision).asNumber().isEqualTo(2))
                .hasPathSatisfying("$.revisions[1].change", change -> assertThat(change).asString().isEqualTo("UPDATED"))
                .hasPathSatisfying("$.revisions[1].source", source -> assertThat(source).asString().isEqualTo("API"))
                .hasPathSatisfying("$.revisions[1].changedAt", time -> assertThat(time).asString()
                        .isEqualTo("2026-10-09T12:00:00Z"))
                .hasPathSatisfying("$.revisions[1].person.name", name -> assertThat(name).asString()
                        .isEqualTo("Edward Thatch"))
                .doesNotHavePath("$.revisions[1].person.birth");
    }

    /** Answers 404 when no person with the id has ever existed. */
    @Test
    void answersNotFoundForRevisionsOfUnknownPerson() {
        given(service.revisions(ID)).willThrow(new PersonNotFoundException(ID));

        assertThat(mvc.get().uri("/api/v1/persons/{id}/revisions", ID))
                .hasStatus(HttpStatus.NOT_FOUND)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
    }

    /** Returns the requested page with paging information. */
    @Test
    void listsOnePageOfPersons() {
        Person teach = stored(new Person("Edward Teach", Gender.MALE), ID, 0);
        given(service.list(1, 10)).willReturn(new PageImpl<>(List.of(teach), PageRequest.of(1, 10), 11));

        assertThat(mvc.get().uri("/api/v1/persons?page=1&size=10"))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.content[0].name", name -> assertThat(name).asString().isEqualTo("Edward Teach"))
                .hasPathSatisfying("$.page", page -> assertThat(page).asNumber().isEqualTo(1))
                .hasPathSatisfying("$.totalElements", total -> assertThat(total).asNumber().isEqualTo(11))
                .hasPathSatisfying("$.totalPages", pages -> assertThat(pages).asNumber().isEqualTo(2));
    }

    /** Uses page 0 and 20 persons per page when the client does not ask for anything else. */
    @Test
    void usesDefaultPaging() {
        given(service.list(0, 20)).willReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        assertThat(mvc.get().uri("/api/v1/persons")).hasStatusOk();
    }

    /** Answers 400 as Problem Details when paging parameters are outside the limits in the contract. */
    @Test
    void answersBadRequestForInvalidPaging() {
        assertThat(mvc.get().uri("/api/v1/persons?page=-1"))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .hasPathSatisfying("$.detail", detail -> assertThat(detail).asString().contains("page"));
        assertThat(mvc.get().uri("/api/v1/persons?size=101"))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .hasPathSatisfying("$.detail", detail -> assertThat(detail).asString().contains("size"));
    }

    /** Creates a person and answers 201 with its URL, its version as ETag and the stored data. */
    @Test
    void createsPerson() {
        given(service.create(any())).willAnswer(invocation -> stored(invocation.getArgument(0), ID, 0));

        assertThat(post("""
                {
                  "name": "Edward Teach",
                  "alsoKnownAs": [{ "name": "Blackbeard", "type": "NICKNAME" }],
                  "birth": { "year": 1680, "uncertaintyYears": 5 },
                  "death": { "year": 1718, "month": 11, "day": 22 },
                  "birthCountry": "GB",
                  "gender": "MALE"
                }"""))
                .hasStatus(HttpStatus.CREATED)
                .hasHeader("Location", "http://localhost/api/v1/persons/" + ID)
                .hasHeader("ETag", "\"0\"")
                .bodyJson()
                .hasPathSatisfying("$.alsoKnownAs[0].name", name -> assertThat(name).asString().isEqualTo("Blackbeard"))
                .hasPathSatisfying("$.birth.display", text -> assertThat(text).asString().isEqualTo("1680 ± 5"))
                .hasPathSatisfying("$.ageAtDeath.max", max -> assertThat(max).asNumber().isEqualTo(43))
                .hasPathSatisfying("$.birthCountry.name", country -> assertThat(country).asString().isEqualTo("United Kingdom"));
    }

    /** Answers 409 as Problem Details, naming the existing person, when the person is already stored. */
    @Test
    void rejectsDuplicatePerson() {
        given(service.create(any())).willThrow(new DuplicatePersonException(ID, "Wikidata id Q213518"));

        assertThat(post("""
                { "name": "Edward Teach", "wikidataId": "Q213518", "gender": "MALE" }"""))
                .hasStatus(HttpStatus.CONFLICT)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .hasPathSatisfying("$.detail", detail -> assertThat(detail).asString().contains(ID.toString()));
    }

    /** Answers 422 with one field error per broken schema rule, so the client can fix all at once. */
    @Test
    void rejectsBodyThatBreaksTheSchema() {
        assertThat(post("""
                { "birth": { "year": 1680, "month": 13 }, "birthCountry": "gb", "gender": "MALE" }"""))
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .hasPathSatisfying("$.errors[*].field", fields -> assertThat(fields).asInstanceOf(LIST)
                        .containsExactly("birth.month", "birthCountry", "name"));
        then(service).shouldHaveNoInteractions();
    }

    /** Answers 422 for data the schema allows but that cannot be true, naming each field. */
    @Test
    void rejectsImpossibleData() {
        assertThat(post("""
                {
                  "name": "Edward Teach",
                  "birth": { "year": 1718, "month": 4, "day": 31 },
                  "birthCountry": "XX",
                  "gender": "MALE"
                }"""))
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .bodyJson()
                .hasPathSatisfying("$.errors[*].field", fields -> assertThat(fields).asInstanceOf(LIST)
                        .containsExactly("birth", "birthCountry"));
        then(service).shouldHaveNoInteractions();
    }

    /** Answers 422 when death can only have happened before birth. */
    @Test
    void rejectsDeathBeforeBirth() {
        assertThat(post("""
                { "name": "Edward Teach", "birth": { "year": 1718 }, "death": { "year": 1680 }, "gender": "MALE" }"""))
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .bodyJson()
                .hasPathSatisfying("$.errors[0].field", field -> assertThat(field).asString().isEqualTo("death"));
    }

    /** Answers 422 naming the field when a value is not one of the allowed ones. */
    @Test
    void rejectsUnknownEnumValue() {
        assertThat(post("""
                { "name": "Edward Teach", "gender": "PIRATE" }"""))
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .bodyJson()
                .hasPathSatisfying("$.errors[0].field", field -> assertThat(field).asString().isEqualTo("gender"));
    }

    /** Answers 400 when the body is not valid JSON at all, since nothing in it can be trusted. */
    @Test
    void rejectsMalformedJson() {
        assertThat(post("{ \"name\": "))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
    }

    /** Replaces a person when If-Match names the current version, and answers with the new version. */
    @Test
    void replacesPersonWhenVersionMatches() {
        given(service.replace(eq(ID), eq(3L), any())).willAnswer(invocation -> {
            Person person = stored(new Person("Edward Teach", Gender.MALE), ID, 3);
            invocation.<Consumer<Person>>getArgument(2).accept(person);
            return stored(person, ID, 4);
        });

        assertThat(put("\"3\"", """
                { "name": "Edward Thatch", "gender": "MALE" }"""))
                .hasStatusOk()
                .hasHeader("ETag", "\"4\"")
                .bodyJson()
                .hasPathSatisfying("$.name", name -> assertThat(name).asString().isEqualTo("Edward Thatch"));
    }

    /** Answers 428 when If-Match is missing, since the client must show which version it read. */
    @Test
    void requiresIfMatchForReplace() {
        assertThat(put(null, "{ \"name\": \"Edward Teach\", \"gender\": \"MALE\" }"))
                .hasStatus(HttpStatus.PRECONDITION_REQUIRED)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        then(service).shouldHaveNoInteractions();
    }

    /** Answers 412 when the person has been changed since the client read it. */
    @Test
    void rejectsReplaceOfChangedPerson() {
        given(service.replace(eq(ID), eq(3L), any())).willThrow(new PersonChangedException(3, 4));

        assertThat(put("\"3\"", "{ \"name\": \"Edward Teach\", \"gender\": \"MALE\" }"))
                .hasStatus(HttpStatus.PRECONDITION_FAILED)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .hasPathSatisfying("$.detail", detail -> assertThat(detail).asString().contains("version 4"));
    }

    /** Answers 412 for If-Match values that cannot name a version, including weak ETags and "*". */
    @ParameterizedTest
    @ValueSource(strings = {"W/\"3\"", "*", "3", "\"three\""})
    void rejectsUnusableIfMatch(String ifMatch) {
        assertThat(put(ifMatch, "{ \"name\": \"Edward Teach\", \"gender\": \"MALE\" }"))
                .hasStatus(HttpStatus.PRECONDITION_FAILED);
        then(service).shouldHaveNoInteractions();
    }

    /** Answers 404 when replacing a person that does not exist. */
    @Test
    void answersNotFoundWhenReplacingUnknownPerson() {
        given(service.replace(eq(ID), eq(0L), any())).willThrow(new PersonNotFoundException(ID));

        assertThat(put("\"0\"", "{ \"name\": \"Edward Teach\", \"gender\": \"MALE\" }"))
                .hasStatus(HttpStatus.NOT_FOUND);
    }

    /** Deletes a person and answers 204 without a body. */
    @Test
    void deletesPerson() {
        assertThat(mvc.delete().uri("/api/v1/persons/{id}", ID).with(httpBasic("admin", "test-password")))
                .hasStatus(HttpStatus.NO_CONTENT);
        then(service).should().delete(ID);
    }

    /** Answers 404 when deleting a person that does not exist. */
    @Test
    void answersNotFoundWhenDeletingUnknownPerson() {
        willThrow(new PersonNotFoundException(ID)).given(service).delete(ID);

        assertThat(mvc.delete().uri("/api/v1/persons/{id}", ID).with(httpBasic("admin", "test-password")))
                .hasStatus(HttpStatus.NOT_FOUND)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
    }

    /** Sends a replacement as the admin, with the given If-Match header unless it is {@code null}. */
    private MockMvcTester.MockMvcRequestBuilder put(String ifMatch, String json) {
        MockMvcTester.MockMvcRequestBuilder request = mvc.put().uri("/api/v1/persons/{id}", ID)
                .with(httpBasic("admin", "test-password"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json);
        return ifMatch == null ? request : request.header("If-Match", ifMatch);
    }

    /** Sends a person as the admin. */
    private MockMvcTester.MockMvcRequestBuilder post(String json) {
        return mvc.post().uri("/api/v1/persons")
                .with(httpBasic("admin", "test-password"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json);
    }
}
