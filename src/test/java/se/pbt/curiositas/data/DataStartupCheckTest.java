package se.pbt.curiositas.data;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.diagnostics.FailureAnalyzer;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import se.pbt.curiositas.person.Gender;
import se.pbt.curiositas.person.Person;
import se.pbt.curiositas.person.PersonHistory;
import se.pbt.curiositas.person.PersonRepository;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static se.pbt.curiositas.person.StoredPersons.stored;

/**
 * Verifies that the application refuses to start when the database and the data files differ,
 * and that the refusal explains what differs and how to fix it instead of showing a stack trace.
 */
class DataStartupCheckTest {

    private static final UUID BONNY_ID = UUID.fromString("3b0f7d2e-5a61-4c8e-9f12-6d4e8a7b1c90");

    private final PersonRepository repository = mock(PersonRepository.class);

    private final PersonHistory history = mock(PersonHistory.class);

    @TempDir
    private Path directory;

    /** Starts normally when the database and the files match. */
    @Test
    void startsWhenDataMatches() {
        List<Person> persons = List.of(bonny());
        new PersonExporter().export(persons, directory.resolve("persons"));
        given(repository.findAll()).willReturn(persons);
        given(history.personsWithHistory()).willReturn(Set.of(BONNY_ID));

        assertThatNoException().isThrownBy(() -> check().afterSingletonsInstantiated());
    }

    /** Refuses to start when they differ, naming the differences. */
    @Test
    void refusesToStartWhenDataDiffers() {
        given(repository.findAll()).willReturn(List.of(bonny()));
        given(history.personsWithHistory()).willReturn(Set.of(BONNY_ID));

        assertThatExceptionOfType(DataOutOfSyncException.class)
                .isThrownBy(() -> check().afterSingletonsInstantiated())
                .withMessageContaining("anne-bonny.json")
                .withMessageContaining("only in the database: Anne Bonny");
    }

    /** Refuses to start when a person has no history, even though the files match. */
    @Test
    void refusesToStartWhenPersonHasNoHistory() {
        List<Person> persons = List.of(bonny());
        new PersonExporter().export(persons, directory.resolve("persons"));
        given(repository.findAll()).willReturn(persons);
        given(history.personsWithHistory()).willReturn(Set.of());

        assertThatExceptionOfType(DataOutOfSyncException.class)
                .isThrownBy(() -> check().afterSingletonsInstantiated())
                .withMessageContaining("anne-bonny.json")
                .withMessageContaining("no history in the database: Anne Bonny");
    }

    /**
     * Explains the refusal in the startup failure report: what differs in the description, and
     * the commands that fix it in the action.
     */
    @Test
    void explainsRefusalWithDifferencesAndCommands() {
        DataOutOfSyncException failure = new DataOutOfSyncException(new DataDifferences(
                Map.of("data/persons/sun-tzu.json", List.of("only in the files"))));

        FailureAnalysis analysis = new DataOutOfSyncFailureAnalyzer().analyze(failure);

        assertThat(analysis.getDescription())
                .contains("source of truth")
                .contains("data/persons/sun-tzu.json")
                .contains("only in the files");
        assertThat(analysis.getAction())
                .contains(".\\data.cmd import")
                .contains(".\\data.cmd export")
                .contains("git diff");
    }

    /** Registers the explanation with Spring Boot, so it is used when the application fails to start. */
    @Test
    void registersFailureAnalyzer() throws IOException {
        Properties factories = PropertiesLoaderUtils.loadProperties(new ClassPathResource("META-INF/spring.factories"));

        assertThat(factories.getProperty(FailureAnalyzer.class.getName()))
                .contains(DataOutOfSyncFailureAnalyzer.class.getName());
    }

    /** Creates the check against the temporary data folder. */
    private DataStartupCheck check() {
        return new DataStartupCheck(repository, history, new DataProperties(directory));
    }

    /** Creates Anne Bonny as stored in the database. */
    private static Person bonny() {
        return stored(new Person("Anne Bonny", Gender.FEMALE), BONNY_ID, 0);
    }
}
