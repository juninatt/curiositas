package se.pbt.curiositas.person;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import se.pbt.curiositas.TestcontainersConfiguration;
import se.pbt.curiositas.date.HistoricalDate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Verifies that every change made through the service is recorded as a revision, with the whole
 * person, so the history can always tell what a person looked like and how it changed.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfiguration.class, PersonService.class, PersonHistory.class})
class PersonHistoryTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private PersonService service;

    @Autowired
    private PersonHistory history;

    @Autowired
    private TestEntityManager entityManager;

    /** Records a new person as revision 1, created through the API at the time it was stored. */
    @Test
    void recordsCreation() {
        Person created = service.create(teach());

        assertThat(history.of(created.getId())).singleElement().satisfies(revision -> {
            assertThat(revision.getRevision()).isEqualTo(1);
            assertThat(revision.getChangeType()).isEqualTo(ChangeType.CREATED);
            assertThat(revision.getSource()).isEqualTo(ChangeSource.API);
            assertThat(revision.getChangedAt()).isEqualTo(created.getUpdatedAt());
            assertThat(snapshotOf(revision)).isEqualTo(asSnapshot(created));
        });
    }

    /** Records every replacement as the next revision, with the person as it became. */
    @Test
    void recordsEveryReplacement() {
        Person created = service.create(teach());
        service.replace(created.getId(), 0, person -> person.setName("Edward Thatch"));
        Person replaced = service.replace(created.getId(), 1, person -> person.setBirthPlace("Bristol"));

        List<PersonRevision> revisions = history.of(created.getId());

        assertThat(revisions).extracting(PersonRevision::getRevision).containsExactly(1, 2, 3);
        assertThat(revisions).extracting(PersonRevision::getChangeType)
                .containsExactly(ChangeType.CREATED, ChangeType.UPDATED, ChangeType.UPDATED);
        assertThat(revisions).extracting(PersonRevision::getSequenceNumber).isSorted().doesNotHaveDuplicates();
        assertThat(snapshotOf(revisions.get(1)).get("name").asString()).isEqualTo("Edward Thatch");
        assertThat(snapshotOf(revisions.get(1)).has("birthPlace")).isFalse();
        assertThat(snapshotOf(revisions.get(2))).isEqualTo(asSnapshot(replaced));
        assertThat(revisions.get(2).getChangedAt()).isEqualTo(replaced.getUpdatedAt());
    }

    /** Adds no revision when a replacement sends the same data, since nothing changed. */
    @Test
    void addsNoRevisionWhenNothingChanges() {
        Person created = service.create(teach());
        startNewRequest();

        Person replaced = service.replace(created.getId(), 0, person -> person.setName("Edward Teach"));

        assertThat(replaced.getVersion()).isZero();
        assertThat(history.of(created.getId())).hasSize(1);
    }

    /** Keeps the history of a deleted person, ending with the person as it was before the deletion. */
    @Test
    void keepsHistoryOfDeletedPerson() {
        Person created = service.create(teach());
        UUID id = created.getId();
        JsonNode beforeDeletion = asSnapshot(created);

        service.delete(id);
        startNewRequest();

        assertThatExceptionOfType(PersonDeletedException.class).isThrownBy(() -> service.get(id));
        assertThat(history.of(id)).extracting(PersonRevision::getChangeType)
                .containsExactly(ChangeType.CREATED, ChangeType.DELETED);
        PersonRevision deletion = history.of(id).getLast();
        assertThat(deletion.getSource()).isEqualTo(ChangeSource.API);
        assertThat(snapshotOf(deletion)).isEqualTo(beforeDeletion);
    }

    /** Adds no revision for changes that are rejected, since they never happened. */
    @Test
    void addsNoRevisionForRejectedChanges() {
        Person created = service.create(teach());
        UUID id = created.getId();

        assertThatExceptionOfType(PersonChangedException.class)
                .isThrownBy(() -> service.replace(id, 5, person -> person.setName("Edward Thatch")));
        assertThatExceptionOfType(DuplicatePersonException.class).isThrownBy(() -> service.create(teach()));
        assertThatExceptionOfType(PersonNotFoundException.class).isThrownBy(() -> service.delete(UUID.randomUUID()));

        assertThat(history.of(id)).hasSize(1);
    }

    /** Numbers each person's revisions from 1, while the sequence numbers order all changes together. */
    @Test
    void numbersRevisionsPerPersonAndChangesOverall() {
        Person teach = service.create(teach());
        Person bonny = service.create(new Person("Anne Bonny", Gender.FEMALE));
        service.replace(teach.getId(), 0, person -> person.setName("Edward Thatch"));

        List<PersonRevision> teachRevisions = history.of(teach.getId());
        List<PersonRevision> bonnyRevisions = history.of(bonny.getId());

        assertThat(teachRevisions).extracting(PersonRevision::getRevision).containsExactly(1, 2);
        assertThat(bonnyRevisions).extracting(PersonRevision::getRevision).containsExactly(1);
        assertThat(bonnyRevisions.getFirst().getSequenceNumber())
                .isGreaterThan(teachRevisions.getFirst().getSequenceNumber())
                .isLessThan(teachRevisions.getLast().getSequenceNumber());
    }

    /** Stores snapshots in the same form as the data files, leaving unknown values out. */
    @Test
    void leavesUnknownValuesOutOfSnapshots() {
        Person created = service.create(new Person("Anne Bonny", Gender.FEMALE));

        String snapshot = history.of(created.getId()).getFirst().getSnapshot();

        assertThat(JSON.readTree(snapshot).propertyNames()).containsExactlyInAnyOrder("name", "gender");
    }

    /** Returns no revisions for a person that never existed, and the service answers that it was not found. */
    @Test
    void hasNoRevisionsForUnknownPerson() {
        UUID unknown = UUID.randomUUID();

        assertThat(history.of(unknown)).isEmpty();
        assertThatExceptionOfType(PersonNotFoundException.class).isThrownBy(() -> service.revisions(unknown));
    }

    /**
     * Tells a deleted person apart from one that never existed for every operation, naming when
     * it was deleted and where its history is.
     */
    @Test
    void reportsDeletedPersonAsDeleted() {
        UUID id = service.create(teach()).getId();
        service.delete(id);
        startNewRequest();
        Instant deletedAt = history.of(id).getLast().getChangedAt();

        assertThatExceptionOfType(PersonDeletedException.class).isThrownBy(() -> service.get(id))
                .withMessageContaining(id.toString())
                .withMessageContaining(deletedAt.toString())
                .withMessageContaining("/api/v1/persons/" + id + "/revisions");
        assertThatExceptionOfType(PersonDeletedException.class)
                .isThrownBy(() -> service.replace(id, 0, person -> person.setName("Edward Thatch")));
        assertThatExceptionOfType(PersonDeletedException.class).isThrownBy(() -> service.delete(id));
        assertThat(history.of(id)).hasSize(2);
    }

    /** Lets the service list the revisions of a deleted person, since its history is kept. */
    @Test
    void listsRevisionsOfDeletedPerson() {
        UUID id = service.create(teach()).getId();
        service.delete(id);
        startNewRequest();

        assertThat(service.revisions(id)).extracting(PersonRevision::getChangeType)
                .containsExactly(ChangeType.CREATED, ChangeType.DELETED);
    }

    /** Reads a revision's snapshot as JSON. */
    private static JsonNode snapshotOf(PersonRevision revision) {
        return JSON.readTree(revision.getSnapshot());
    }

    /** Returns the person as JSON in the same form as the data files, as a snapshot should hold it. */
    private static JsonNode asSnapshot(Person person) {
        return JSON.valueToTree(PersonMapper.toInput(person));
    }

    /**
     * Writes pending changes and forgets the loaded persons, as between two requests. Without it,
     * Hibernate compares a replacement with the state it inserted moments before in the same
     * transaction, which never happens in the application.
     */
    private void startNewRequest() {
        entityManager.flush();
        entityManager.clear();
    }

    /** Creates Edward Teach, born around 1680. */
    private static Person teach() {
        Person teach = new Person("Edward Teach", Gender.MALE);
        teach.setBirth(new HistoricalDate(1680, null, null, 5));
        teach.setWikidataId("Q213518");
        return teach;
    }
}
