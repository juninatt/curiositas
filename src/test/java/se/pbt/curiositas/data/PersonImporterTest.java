package se.pbt.curiositas.data;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Import;
import se.pbt.curiositas.TestcontainersConfiguration;
import se.pbt.curiositas.date.HistoricalDate;
import se.pbt.curiositas.person.Gender;
import se.pbt.curiositas.person.Person;
import se.pbt.curiositas.person.ChangeSource;
import se.pbt.curiositas.person.ChangeType;
import se.pbt.curiositas.person.PersonHistory;
import se.pbt.curiositas.person.PersonRepository;
import se.pbt.curiositas.person.PersonRevision;
import se.pbt.curiositas.person.PersonRevisionRepository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Verifies that an import makes the database mirror the data files, since the files are the
 * source of truth and the database must never hold anything else.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(ValidationAutoConfiguration.class)
@Import({TestcontainersConfiguration.class, PersonImporter.class, PersonHistory.class})
class PersonImporterTest {

    private static final String TEACH = """
            {
              "name": "Edward Teach",
              "wikidataId": "Q213518",
              "alsoKnownAs": [{ "name": "Blackbeard", "type": "NICKNAME" }],
              "birth": { "year": 1680, "uncertaintyYears": 5 },
              "death": { "year": 1718, "month": 11, "day": 22 },
              "gender": "MALE"
            }
            """;

    private static final String BONNY = """
            { "name": "Anne Bonny", "birth": { "year": 1697, "uncertaintyYears": 3 }, "gender": "FEMALE" }
            """;

    private static final String CAESAR = """
            { "name": "Julius Caesar", "wikidataId": "Q1048", "birth": { "year": -99, "month": 7 }, "gender": "MALE" }
            """;

    @Autowired
    private PersonImporter importer;

    @Autowired
    private PersonRepository repository;

    @Autowired
    private PersonHistory history;

    @Autowired
    private PersonRevisionRepository revisions;

    @Autowired
    private TestEntityManager entityManager;

    @TempDir
    private Path directory;

    /** Creates a person for every file when the database is empty. */
    @Test
    void createsPersonsFromFiles() throws IOException {
        write("edward-teach-q213518.json", TEACH);
        write("anne-bonny-1697.json", BONNY);

        PersonImporter.Result result = importer.importFrom(directory);

        assertThat(result).isEqualTo(new PersonImporter.Result(2, 0, 0, 0));
        Person teach = repository.findByWikidataId("Q213518").orElseThrow();
        assertThat(teach.getBirth()).isEqualTo(new HistoricalDate(1680, null, null, 5));
        assertThat(teach.getAlsoKnownAs()).hasSize(1);
    }

    /**
     * Updates matched persons in place, keeping their ids, and deletes persons without a file, so
     * the database mirrors the files exactly.
     */
    @Test
    void mirrorsFilesAndKeepsIds() throws IOException {
        write("edward-teach-q213518.json", TEACH);
        write("anne-bonny-1697.json", BONNY);
        importer.importFrom(directory);
        UUID teachId = repository.findByWikidataId("Q213518").orElseThrow().getId();

        write("edward-teach-q213518.json", TEACH.replace("1680", "1681"));
        Files.delete(directory.resolve("anne-bonny-1697.json"));
        PersonImporter.Result result = importer.importFrom(directory);

        assertThat(result).isEqualTo(new PersonImporter.Result(0, 1, 0, 1));
        entityManager.clear();
        assertThat(repository.findAll()).singleElement().satisfies(teach -> {
            assertThat(teach.getId()).isEqualTo(teachId);
            assertThat(teach.getBirth().year()).isEqualTo(1681);
        });
    }

    /** Leaves persons that already match their files untouched, so nothing changes needlessly. */
    @Test
    void leavesMatchingPersonsUnchanged() throws IOException {
        write("anne-bonny-1697.json", BONNY);
        importer.importFrom(directory);
        long version = repository.findAll().getFirst().getVersion();

        PersonImporter.Result result = importer.importFrom(directory);

        assertThat(result).isEqualTo(new PersonImporter.Result(0, 0, 1, 0));
        assertThat(repository.findAll().getFirst().getVersion()).isEqualTo(version);
    }

    /**
     * Records a revision with the source IMPORT for every person the import creates, changes or
     * deletes, and none for persons that already match their files.
     */
    @Test
    void recordsImportedChangesInTheHistory() throws IOException {
        write("edward-teach-q213518.json", TEACH);
        write("anne-bonny-1697.json", BONNY);
        write("julius-caesar-q1048.json", CAESAR);
        importer.importFrom(directory);
        UUID teachId = repository.findByWikidataId("Q213518").orElseThrow().getId();
        UUID bonnyId = idOf("Anne Bonny");
        UUID caesarId = repository.findByWikidataId("Q1048").orElseThrow().getId();

        write("edward-teach-q213518.json", TEACH.replace("1680", "1681"));
        Files.delete(directory.resolve("anne-bonny-1697.json"));
        importer.importFrom(directory);

        assertThat(history.of(teachId)).extracting(PersonRevision::getChangeType)
                .containsExactly(ChangeType.CREATED, ChangeType.UPDATED);
        assertThat(history.of(bonnyId)).extracting(PersonRevision::getChangeType)
                .containsExactly(ChangeType.CREATED, ChangeType.DELETED);
        assertThat(history.of(caesarId)).extracting(PersonRevision::getChangeType)
                .containsExactly(ChangeType.CREATED);
        assertThat(revisions.findAll()).extracting(PersonRevision::getSource).containsOnly(ChangeSource.IMPORT);
        assertThat(history.of(teachId).getLast().getSnapshot()).contains("1681");
        assertThat(history.of(bonnyId).getLast().getSnapshot()).contains("Anne Bonny");
    }

    /** Keeps a person's id when a Wikidata id is added to its file, since it is still the same person. */
    @Test
    void recognisesPersonWhenWikidataIdIsAdded() throws IOException {
        write("anne-bonny-1697.json", BONNY);
        importer.importFrom(directory);
        UUID bonnyId = repository.findAll().getFirst().getId();

        write("anne-bonny-1697.json", BONNY.replace("\"name\": \"Anne Bonny\",", "\"name\": \"Anne Bonny\", \"wikidataId\": \"Q231549\","));
        importer.importFrom(directory);

        assertThat(repository.findByWikidataId("Q231549")).hasValueSatisfying(bonny -> assertThat(bonny.getId()).isEqualTo(bonnyId));
    }

    /**
     * Reports every problem in every file, with line and column for broken JSON and the field
     * otherwise, and changes nothing in the database.
     */
    @Test
    void reportsAllProblemsAndChangesNothing() throws IOException {
        repository.saveAndFlush(new Person("Already stored", Gender.UNKNOWN));
        write("broken.json", "{ \"name\": \"Edward Teach\",\n  \"gender\": }\n");
        write("schema.json", "{ \"birth\": { \"year\": 1680, \"month\": 13 }, \"gender\": \"MALE\" }");
        write("impossible.json", "{ \"name\": \"X\", \"birth\": { \"year\": 1718, \"month\": 4, \"day\": 31 }, \"gender\": \"MALE\" }");
        write("misspelled.json", "{ \"name\": \"X\", \"birthplace\": \"Bristol\", \"gender\": \"MALE\" }");
        write("unknown-value.json", "{ \"name\": \"X\", \"gender\": \"PIRATE\" }");

        assertThatExceptionOfType(InvalidDataFilesException.class)
                .isThrownBy(() -> importer.importFrom(directory))
                .satisfies(invalid -> {
                    var problems = invalid.getProblemsByFile();
                    assertThat(problems.keySet()).extracting(name -> Path.of(name).getFileName().toString())
                            .containsExactly("broken.json", "impossible.json", "misspelled.json", "schema.json", "unknown-value.json");
                    assertThat(problems).hasEntrySatisfying(key(problems, "broken.json"),
                            list -> assertThat(list).singleElement().asString().startsWith("line 2, column"));
                    assertThat(problems).hasEntrySatisfying(key(problems, "schema.json"),
                            list -> assertThat(list).anySatisfy(problem -> assertThat(problem).startsWith("birth.month:"))
                                    .anySatisfy(problem -> assertThat(problem).startsWith("name:")));
                    assertThat(problems).hasEntrySatisfying(key(problems, "impossible.json"),
                            list -> assertThat(list).singleElement().asString().startsWith("birth:"));
                    assertThat(problems).hasEntrySatisfying(key(problems, "misspelled.json"),
                            list -> assertThat(list).singleElement().asString().isEqualTo("birthplace: is not a known field"));
                    assertThat(problems).hasEntrySatisfying(key(problems, "unknown-value.json"),
                            list -> assertThat(list).singleElement().asString().startsWith("gender:"));
                });
        assertThat(repository.findAll()).extracting(Person::getName).containsExactly("Already stored");
        assertThat(revisions.count()).isZero();
    }

    /** Reports two files that describe the same person, since the database could not hold both. */
    @Test
    void reportsFilesDescribingTheSamePerson() throws IOException {
        write("edward-teach-q213518.json", TEACH);
        write("blackbeard.json", TEACH.replace("Edward Teach", "Blackbeard"));

        assertThatExceptionOfType(InvalidDataFilesException.class)
                .isThrownBy(() -> importer.importFrom(directory))
                .withMessageContaining("describes the same person as");
    }

    /** Refuses to import from a folder that does not exist, so a wrong path can never empty the database. */
    @Test
    void refusesMissingFolder() {
        repository.saveAndFlush(new Person("Already stored", Gender.UNKNOWN));

        assertThatExceptionOfType(UncheckedIOException.class)
                .isThrownBy(() -> importer.importFrom(directory.resolve("missing")))
                .withMessage("The data folder " + directory.resolve("missing") + " does not exist");
        assertThat(repository.count()).isEqualTo(1);
    }

    /** Returns the id of the stored person with the given name. */
    private UUID idOf(String name) {
        return repository.findAll().stream()
                .filter(person -> person.getName().equals(name))
                .findFirst().orElseThrow()
                .getId();
    }

    /** Writes a data file into the test folder. */
    private void write(String name, String content) throws IOException {
        Files.writeString(directory.resolve(name), content);
    }

    /** Finds the key of a file in the problem list, whatever folder it is reported with. */
    private static String key(Map<String, List<String>> problems, String fileName) {
        return problems.keySet().stream()
                .filter(name -> Path.of(name).getFileName().toString().equals(fileName))
                .findFirst()
                .orElseThrow();
    }
}
