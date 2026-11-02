package se.pbt.curiositas.dataset;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import se.pbt.curiositas.TestcontainersConfiguration;
import se.pbt.curiositas.api.model.PersonDto;
import se.pbt.curiositas.date.HistoricalDate;
import se.pbt.curiositas.person.AlternativeName;
import se.pbt.curiositas.person.ChangeType;
import se.pbt.curiositas.person.Gender;
import se.pbt.curiositas.person.NameType;
import se.pbt.curiositas.person.Person;
import se.pbt.curiositas.person.PersonHistory;
import se.pbt.curiositas.person.PersonMapper;
import se.pbt.curiositas.person.PersonRepository;
import se.pbt.curiositas.person.PersonRevision;
import se.pbt.curiositas.person.PersonRevisionRepository;
import se.pbt.curiositas.person.PersonService;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.BDDMockito.willAnswer;

/**
 * Verifies that a snapshot holds every person together with the version of the dataset, read at
 * the same moment, and that the changes after a version continue exactly from there, so a client
 * that loads the snapshot and then follows the changes misses nothing.
 *
 * <p>Runs without the usual test transaction, so the snapshot gets a transaction of its own.
 */
@DataJpaTest(showSql = false)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfiguration.class, DatasetService.class, PersonService.class, PersonHistory.class})
class DatasetServiceTest {

    @Autowired
    private DatasetService dataset;

    @MockitoSpyBean
    private PersonService persons;

    @Autowired
    private PersonRepository repository;

    @Autowired
    private PersonRevisionRepository revisions;

    /** Removes what the tests committed. */
    @AfterEach
    void cleanUp() {
        repository.deleteAll();
        revisions.deleteAll();
    }

    /** Gives version 0 for a dataset that has never changed. */
    @Test
    void startsAtVersionZero() {
        DatasetService.Snapshot snapshot = dataset.snapshot();

        assertThat(snapshot.version()).isZero();
        assertThat(snapshot.persons()).isEmpty();
    }

    /** Returns every person by name, at the version of the latest change. */
    @Test
    void returnsEveryPersonAtTheLatestVersion() {
        persons.create(new Person("Edward Teach", Gender.MALE));
        Person bonny = persons.create(new Person("Anne Bonny", Gender.FEMALE));

        DatasetService.Snapshot snapshot = dataset.snapshot();

        assertThat(snapshot.persons()).extracting(Person::getName).containsExactly("Anne Bonny", "Edward Teach");
        assertThat(snapshot.version()).isEqualTo(latestSequenceNumberOf(bonny));
    }

    /**
     * Keeps the version and the persons together even when a change is committed between reading
     * one and the other: the snapshot shows the dataset before that change, at the version before it.
     */
    @Test
    void readsVersionAndPersonsAtTheSameMoment() throws Exception {
        Person teach = persons.create(new Person("Edward Teach", Gender.MALE));
        ExecutorService otherClient = Executors.newSingleThreadExecutor();
        willAnswer(invocation -> {
            otherClient.submit(() -> persons.create(new Person("Anne Bonny", Gender.FEMALE)))
                    .get(10, TimeUnit.SECONDS);
            return invocation.callRealMethod();
        }).given(persons).listAll();

        DatasetService.Snapshot snapshot;
        try {
            snapshot = dataset.snapshot();
        } finally {
            otherClient.shutdownNow();
        }

        assertThat(repository.count()).as("the other change was committed").isEqualTo(2);
        assertThat(snapshot.persons()).extracting(Person::getName).containsExactly("Edward Teach");
        assertThat(snapshot.version()).isEqualTo(latestSequenceNumberOf(teach));
    }

    /** Lists every change from the start, deletions included, in the order they were committed. */
    @Test
    void listsEveryChangeFromTheStart() {
        Person teach = persons.create(new Person("Edward Teach", Gender.MALE));
        persons.replace(teach.getId(), 0, person -> person.setBirthPlace("Bristol"));
        Person bonny = persons.create(new Person("Anne Bonny", Gender.FEMALE));
        persons.delete(teach.getId());

        DatasetService.Changes changes = dataset.changesAfter(0, 100);

        assertThat(changes.changes()).extracting(PersonRevision::getChangeType).containsExactly(
                ChangeType.CREATED, ChangeType.UPDATED, ChangeType.CREATED, ChangeType.DELETED);
        assertThat(changes.changes()).extracting(PersonRevision::getSequenceNumber).isSorted();
        assertThat(changes.changes().get(2).getPersonId()).isEqualTo(bonny.getId());
        assertThat(changes.version()).isEqualTo(dataset.snapshot().version());
        assertThat(changes.hasMore()).isFalse();
    }

    /** Hands out the changes a page at a time, each page continuing where the last one stopped. */
    @Test
    void pagesThroughChanges() {
        for (String name : List.of("Edward Teach", "Anne Bonny", "Mary Read")) {
            persons.create(new Person(name, Gender.UNKNOWN));
        }

        DatasetService.Changes first = dataset.changesAfter(0, 2);
        DatasetService.Changes second = dataset.changesAfter(first.version(), 2);

        assertThat(first.changes()).hasSize(2);
        assertThat(first.hasMore()).isTrue();
        assertThat(first.version()).isEqualTo(first.changes().getLast().getSequenceNumber());
        assertThat(second.changes()).hasSize(1);
        assertThat(second.hasMore()).isFalse();
        assertThat(second.version()).isEqualTo(dataset.snapshot().version());
    }

    /** Returns no changes and the same version to a client that is up to date. */
    @Test
    void returnsNothingToClientThatIsUpToDate() {
        persons.create(new Person("Edward Teach", Gender.MALE));
        long latest = dataset.snapshot().version();

        DatasetService.Changes changes = dataset.changesAfter(latest, 100);

        assertThat(changes.changes()).isEmpty();
        assertThat(changes.version()).isEqualTo(latest);
        assertThat(changes.hasMore()).isFalse();
    }

    /** Refuses a version the dataset has not reached, since that copy cannot come from this dataset. */
    @Test
    void refusesVersionBeyondTheLatest() {
        persons.create(new Person("Edward Teach", Gender.MALE));
        long latest = dataset.snapshot().version();

        assertThatExceptionOfType(VersionAheadException.class)
                .isThrownBy(() -> dataset.changesAfter(latest + 1, 100))
                .withMessageContaining("Version " + (latest + 1))
                .withMessageContaining("latest version " + latest);
    }

    /**
     * Shows each person in a change exactly as the person endpoints and the snapshot show it,
     * creation time and derived values included, so a copy kept in sync matches one freshly loaded.
     */
    @Test
    void showsPersonInChangeExactlyAsTheSnapshotDoes() {
        Person created = persons.create(teach());
        persons.replace(created.getId(), 0, person -> person.setDeathPlace("Ocracoke Island"));

        PersonDto inChange = PersonMapper.toDto(dataset.changesAfter(0, 100).changes().getLast());
        PersonDto inSnapshot = PersonMapper.toDto(dataset.snapshot().persons().getFirst());

        assertThat(inChange).isEqualTo(inSnapshot);
        assertThat(inChange.getAgeAtDeath()).isNotNull();
    }

    /** Creates Edward Teach with every kind of field filled in. */
    private static Person teach() {
        Person teach = new Person("Edward Teach", Gender.MALE);
        teach.setWikidataId("Q213518");
        teach.setAlsoKnownAs(List.of(new AlternativeName("Blackbeard", NameType.NICKNAME)));
        teach.setBirth(new HistoricalDate(1680, null, null, 5));
        teach.setDeath(new HistoricalDate(1718, 11, 22, 0));
        teach.setBirthPlace("probably Bristol");
        teach.setBirthCountry("GB");
        return teach;
    }

    /** Returns the sequence number of the person's latest revision. */
    private long latestSequenceNumberOf(Person person) {
        return revisions.findByPersonIdOrderByRevision(person.getId()).getLast().getSequenceNumber();
    }
}
