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
import se.pbt.curiositas.person.Gender;
import se.pbt.curiositas.person.Person;
import se.pbt.curiositas.person.PersonHistory;
import se.pbt.curiositas.person.PersonRepository;
import se.pbt.curiositas.person.PersonRevisionRepository;
import se.pbt.curiositas.person.PersonService;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.willAnswer;

/**
 * Verifies that a snapshot holds every person together with the version of the dataset, read at
 * the same moment, so a client that loads it and then follows the changes misses nothing.
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

    /** Returns the sequence number of the person's latest revision. */
    private long latestSequenceNumberOf(Person person) {
        return revisions.findByPersonIdOrderByRevision(person.getId()).getLast().getSequenceNumber();
    }
}
