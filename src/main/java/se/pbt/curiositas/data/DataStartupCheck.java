package se.pbt.curiositas.data;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;
import se.pbt.curiositas.person.PersonHistory;
import se.pbt.curiositas.person.PersonRepository;

/**
 * Refuses to start when the database does not match the data files, or when a person has no
 * history, so a problem shows up at once as an error instead of being found later in the data.
 * Runs before the web server starts, so no request is ever answered with data that disagrees
 * with the files.
 *
 * <p>Active together with the file sync, that is in local development; the data commands, which
 * are how differences are fixed, run without it.
 */
@Component
@ConditionalOnBooleanProperty("curiositas.data.sync")
public class DataStartupCheck implements SmartInitializingSingleton {

    private final PersonRepository repository;
    private final PersonHistory history;
    private final DataProperties properties;

    /**
     * Creates the check.
     *
     * @param repository reads the persons in the database
     * @param history    tells which persons have a history
     * @param properties where the data files live
     */
    public DataStartupCheck(PersonRepository repository, PersonHistory history, DataProperties properties) {
        this.repository = repository;
        this.history = history;
        this.properties = properties;
    }

    /**
     * Compares the database with the files, and checks that every person has a history, once
     * everything is set up.
     *
     * @throws DataOutOfSyncException if they differ, explained by {@link DataOutOfSyncFailureAnalyzer}
     */
    @Override
    public void afterSingletonsInstantiated() {
        DataDifferences differences = PersonDataChecker.check(repository.findAll(), history.personsWithHistory(),
                properties.personsDirectory());
        if (!differences.isEmpty()) {
            throw new DataOutOfSyncException(differences);
        }
    }
}
