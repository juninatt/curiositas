package se.pbt.curiositas.dataset;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import se.pbt.curiositas.person.ChangeType;
import se.pbt.curiositas.person.Gender;
import se.pbt.curiositas.person.Person;
import se.pbt.curiositas.security.SecurityConfig;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.LIST;
import static org.mockito.BDDMockito.given;
import static se.pbt.curiositas.person.StoredPersons.stored;
import static se.pbt.curiositas.person.StoredPersons.storedRevision;

/**
 * Verifies the dataset endpoints at the HTTP level: open to everyone, with persons in the same form
 * as the person endpoints, and the paging the contract describes. The service is replaced by a mock.
 */
@WebMvcTest(DatasetController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = "curiositas.admin.password=test-password")
class DatasetControllerTest {

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private DatasetService service;

    /** Returns the version and every person, with derived values, without logging in. */
    @Test
    void returnsSnapshot() {
        Person bonny = stored(new Person("Anne Bonny", Gender.FEMALE), UUID.randomUUID(), 0);
        given(service.snapshot()).willReturn(new DatasetService.Snapshot(57, List.of(bonny)));

        assertThat(mvc.get().uri("/api/v1/snapshot"))
                .hasStatusOk()
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .bodyJson()
                .hasPathSatisfying("$.version", version -> assertThat(version).asNumber().isEqualTo(57))
                .hasPathSatisfying("$.persons[*].name", names -> assertThat(names).asInstanceOf(LIST)
                        .containsExactly("Anne Bonny"))
                .hasPathSatisfying("$.persons[0].createdAt", time -> assertThat(time).asString()
                        .isEqualTo("2026-10-09T12:00:00Z"));
    }

    /** Returns the changes with the version reached and whether more wait; a deletion only names the person. */
    @Test
    void returnsChanges() {
        UUID id = UUID.randomUUID();
        Person bonny = stored(new Person("Anne Bonny", Gender.FEMALE), id, 0);
        given(service.changesAfter(57, 100)).willReturn(new DatasetService.Changes(List.of(
                storedRevision(58, bonny, 1, ChangeType.CREATED),
                storedRevision(59, bonny, 2, ChangeType.DELETED)), 59, true));

        assertThat(mvc.get().uri("/api/v1/changes?after=57"))
                .hasStatusOk()
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .bodyJson()
                .hasPathSatisfying("$.version", version -> assertThat(version).asNumber().isEqualTo(59))
                .hasPathSatisfying("$.hasMore", more -> assertThat(more).asBoolean().isTrue())
                .hasPathSatisfying("$.changes[*].recordType", types -> assertThat(types).asInstanceOf(LIST)
                        .containsExactly("PERSON", "PERSON"))
                .hasPathSatisfying("$.changes[0].person.name", name -> assertThat(name).asString()
                        .isEqualTo("Anne Bonny"))
                .hasPathSatisfying("$.changes[1].id", changed -> assertThat(changed).asString()
                        .isEqualTo(id.toString()))
                .doesNotHavePath("$.changes[1].person");
    }

    /** Asks for every change from the start, 100 at a time, when the client gives nothing else. */
    @Test
    void startsFromTheBeginningByDefault() {
        given(service.changesAfter(0, 100)).willReturn(new DatasetService.Changes(List.of(), 0, false));

        assertThat(mvc.get().uri("/api/v1/changes")).hasStatusOk();
    }

    /** Answers 400 for a version beyond the latest, telling the client to load the snapshot again. */
    @Test
    void answersBadRequestForVersionAhead() {
        given(service.changesAfter(99, 100)).willThrow(new VersionAheadException(99, 57));

        assertThat(mvc.get().uri("/api/v1/changes?after=99"))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .hasPathSatisfying("$.detail", detail -> assertThat(detail).asString()
                        .contains("/api/v1/snapshot"));
    }

    /** Answers 400 for paging outside the limits in the contract. */
    @Test
    void answersBadRequestForInvalidPaging() {
        assertThat(mvc.get().uri("/api/v1/changes?after=-1")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(mvc.get().uri("/api/v1/changes?limit=1001")).hasStatus(HttpStatus.BAD_REQUEST);
    }
}
