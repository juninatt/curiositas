package se.pbt.curiositas.data;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.FileSystemUtils;
import se.pbt.curiositas.TestcontainersConfiguration;
import se.pbt.curiositas.date.HistoricalDate;
import se.pbt.curiositas.person.Gender;
import se.pbt.curiositas.person.Person;
import se.pbt.curiositas.person.PersonHistory;
import se.pbt.curiositas.person.PersonRepository;
import se.pbt.curiositas.person.PersonRevisionRepository;
import se.pbt.curiositas.person.PersonService;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Verifies that every change made through the service is written to the data files before it is
 * committed, and that a failure to write rolls the change back, so the files and the database
 * never disagree.
 *
 * <p>Runs without the usual test transaction, since the files are written just before a commit,
 * which a rolled-back test transaction would never reach. The test cleans up after itself instead.
 */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfiguration.class, DataConfig.class, PersonService.class, PersonHistory.class,
        PersonExporter.class, PersonFileSync.class})
class PersonFileSyncTest {

    private static final Path DATA = createTempDirectory();
    private static final Path PERSONS = DATA.resolve("persons");

    @Autowired
    private PersonService service;

    @Autowired
    private PersonRepository repository;

    @Autowired
    private PersonRevisionRepository revisions;

    /** Points the data folder to a temporary folder and switches the sync on. */
    @DynamicPropertySource
    static void dataFolder(DynamicPropertyRegistry registry) {
        registry.add("curiositas.data.directory", DATA::toString);
        registry.add("curiositas.data.sync", () -> "true");
    }

    /** Removes the persons, revisions and files a test left behind, since nothing is rolled back. */
    @AfterEach
    void cleanUp() throws IOException {
        repository.deleteAll();
        revisions.deleteAll();
        FileSystemUtils.deleteRecursively(PERSONS);
    }

    /** Writes the file of a new person. */
    @Test
    void writesFileWhenPersonIsCreated() throws IOException {
        service.create(teach());

        assertThat(fileNames()).containsExactly("edward-teach-1680.json");
        assertThat(Files.readString(PERSONS.resolve("edward-teach-1680.json"))).contains("\"name\": \"Edward Teach\"");
    }

    /** Moves the person to a new file when a change gives it a new file name, removing the old one. */
    @Test
    void rewritesFileWhenPersonIsReplaced() throws IOException {
        Person created = service.create(teach());

        service.replace(created.getId(), 0, person -> person.setWikidataId("Q213518"));

        assertThat(fileNames()).containsExactly("edward-teach-q213518.json");
        assertThat(Files.readString(PERSONS.resolve("edward-teach-q213518.json"))).contains("\"wikidataId\": \"Q213518\"");
    }

    /** Removes the file of a deleted person. */
    @Test
    void removesFileWhenPersonIsDeleted() throws IOException {
        Person created = service.create(teach());

        service.delete(created.getId());

        assertThat(fileNames()).isEmpty();
    }

    /**
     * Rolls the change and its revision back when the file cannot be written, so neither the
     * database nor the history ever gets ahead of the files.
     */
    @Test
    void rollsBackWhenFileCannotBeWritten() throws IOException {
        Files.createDirectories(DATA);
        Files.writeString(PERSONS, "a file where the persons folder should be");

        assertThatExceptionOfType(UncheckedIOException.class).isThrownBy(() -> service.create(teach()));
        assertThat(repository.count()).isZero();
        assertThat(revisions.count()).isZero();
    }

    /** Creates Edward Teach, born around 1680. */
    private static Person teach() {
        Person teach = new Person("Edward Teach", Gender.MALE);
        teach.setBirth(new HistoricalDate(1680, null, null, 5));
        return teach;
    }

    /** Lists the files in the persons folder, or nothing if it does not exist. */
    private static List<String> fileNames() throws IOException {
        if (!Files.isDirectory(PERSONS)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(PERSONS)) {
            return files.map(file -> file.getFileName().toString()).sorted().toList();
        }
    }

    /** Creates the temporary data folder shared by the tests in this class. */
    private static Path createTempDirectory() {
        try {
            return Files.createTempDirectory("curiositas-data");
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
