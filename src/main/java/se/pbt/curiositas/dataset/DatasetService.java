package se.pbt.curiositas.dataset;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import se.pbt.curiositas.person.Person;
import se.pbt.curiositas.person.PersonHistory;
import se.pbt.curiositas.person.PersonRevision;
import se.pbt.curiositas.person.PersonService;

import java.util.List;

/**
 * Reads the whole dataset at once, and the changes made to it since, for clients that keep their
 * own copy of it.
 */
@Service
public class DatasetService {

    private final PersonService persons;
    private final PersonHistory history;

    /**
     * Creates the service.
     *
     * @param persons reads the persons
     * @param history tells the version of the dataset
     */
    public DatasetService(PersonService persons, PersonHistory history) {
        this.persons = persons;
        this.history = history;
    }

    /**
     * Returns every person together with the version of the dataset they belong to.
     *
     * <p>Both are read from the same moment: in repeatable read, every query in the transaction
     * sees the database as it was at the first one, so a change committed in between can neither
     * be counted in the version while missing from the persons, nor the other way around.
     *
     * @return the persons, ordered by name, and the version
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Snapshot snapshot() {
        long version = history.version();
        return new Snapshot(version, persons.listAll());
    }

    /**
     * Returns the changes made after the given version, in the order they were committed.
     *
     * <p>Read in repeatable read, like the snapshot, so whether more changes are waiting is told
     * from the same moment as the changes themselves.
     *
     * @param after the version the client already has, 0 for every change
     * @param limit the most changes to return
     * @return the changes, the version reached by applying them, and whether more are waiting
     * @throws VersionAheadException if the version is beyond the latest, so the client's copy
     *                               cannot come from this dataset
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Changes changesAfter(long after, int limit) {
        long latest = history.version();
        if (after > latest) {
            throw new VersionAheadException(after, latest);
        }
        List<PersonRevision> changes = history.changesAfter(after, limit);
        long reached = changes.isEmpty() ? after : changes.getLast().getSequenceNumber();
        return new Changes(changes, reached, reached < latest);
    }

    /**
     * Changes made after a version.
     *
     * @param changes the changes, oldest first
     * @param version the version reached by applying them
     * @param hasMore whether more changes are waiting
     */
    public record Changes(List<PersonRevision> changes, long version, boolean hasMore) {
    }

    /**
     * The whole dataset at one version.
     *
     * @param version the sequence number of the latest change included, or 0 if there is none
     * @param persons every person, ordered by name
     */
    public record Snapshot(long version, List<Person> persons) {
    }
}
