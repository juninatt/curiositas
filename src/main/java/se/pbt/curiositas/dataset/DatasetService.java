package se.pbt.curiositas.dataset;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import se.pbt.curiositas.person.Person;
import se.pbt.curiositas.person.PersonHistory;
import se.pbt.curiositas.person.PersonService;

import java.util.List;

/**
 * Reads the whole dataset at once, for clients that keep their own copy of it.
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
     * The whole dataset at one version.
     *
     * @param version the sequence number of the latest change included, or 0 if there is none
     * @param persons every person, ordered by name
     */
    public record Snapshot(long version, List<Person> persons) {
    }
}
