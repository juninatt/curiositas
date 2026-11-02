package se.pbt.curiositas.dataset;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import se.pbt.curiositas.person.Gender;
import se.pbt.curiositas.person.Person;
import se.pbt.curiositas.security.SecurityConfig;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.LIST;
import static org.mockito.BDDMockito.given;
import static se.pbt.curiositas.person.StoredPersons.stored;

/**
 * Verifies the snapshot endpoint at the HTTP level: open to everyone, with the version and every
 * person in the same form as the person endpoints. The service is replaced by a mock.
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
}
