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
import se.pbt.curiositas.person.AlternativeName;
import se.pbt.curiositas.person.Gender;
import se.pbt.curiositas.person.NameType;
import se.pbt.curiositas.person.Person;
import se.pbt.curiositas.person.PersonHistory;
import se.pbt.curiositas.person.PersonRepository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves that the data files are a complete backup: exporting and rebuilding an empty database
 * from the files gives back every person with every field, and exporting again gives exactly the
 * same files, byte for byte.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(ValidationAutoConfiguration.class)
@Import({TestcontainersConfiguration.class, PersonExporter.class, PersonImporter.class, PersonHistory.class})
class DataRoundTripTest {

    @Autowired
    private PersonExporter exporter;

    @Autowired
    private PersonImporter importer;

    @Autowired
    private PersonRepository repository;

    @Autowired
    private TestEntityManager entityManager;

    @TempDir
    private Path directory;

    /**
     * Survives export, import into an empty database and export again without a single change.
     * Both the restored persons and the second export are compared: comparing the files alone
     * would miss a field that the export drops, since both exports would drop it.
     */
    @Test
    void exportImportExportLosesNothing() throws IOException {
        List<Person> original = repository.saveAllAndFlush(personsUsingEveryField());
        Path first = directory.resolve("first");
        Path second = directory.resolve("second");

        exporter.export(repository.findAll(), first);
        repository.deleteAll();
        repository.flush();
        entityManager.clear();
        importer.importFrom(first);
        entityManager.clear();
        List<Person> restored = repository.findAll();
        exporter.export(restored, second);

        assertThat(restored)
                .usingRecursiveFieldByFieldElementComparatorIgnoringFields("id", "version", "createdAt", "updatedAt")
                .containsExactlyInAnyOrderElementsOf(original);
        assertThat(contents(second)).isEqualTo(contents(first)).hasSize(original.size());
    }

    /**
     * Returns persons that together use every field and every special case of the format: dates
     * before Christ, partial dates, uncertainty, several kinds of names, accents, non-Latin
     * script, empty fields and two persons whose file names clash.
     */
    private static List<Person> personsUsingEveryField() {
        Person caesar = new Person("Julius Caesar", Gender.MALE);
        caesar.setWikidataId("Q1048");
        caesar.setAlsoKnownAs(List.of(
                new AlternativeName("Gaius Julius Caesar", NameType.BIRTH_NAME),
                new AlternativeName("Gaius Iulius Caesar", NameType.SPELLING_VARIANT)));
        caesar.setBirth(new HistoricalDate(-99, 7, null, 0));
        caesar.setDeath(new HistoricalDate(-43, 3, 15, 0));
        caesar.setBirthPlace("Rome");
        caesar.setDeathPlace("Theatre of Pompey, Rome");
        caesar.setBirthCountry("IT");

        Person bonny = new Person("Anne Bonny", Gender.FEMALE);
        bonny.setBirth(new HistoricalDate(1697, null, null, 3));
        bonny.setBirthPlace("probably Kinsale");
        bonny.setBirthCountry("IE");

        Person cortes = new Person("Hernán Cortés", Gender.MALE);
        cortes.setAlsoKnownAs(List.of(new AlternativeName("Hernando Cortés", NameType.SPELLING_VARIANT)));
        cortes.setBirth(new HistoricalDate(1485, null, null, 0));
        cortes.setFloruit(new HistoricalDate(1519, null, null, 2));

        Person sunTzu = new Person("孫武", Gender.MALE);
        sunTzu.setAlsoKnownAs(List.of(new AlternativeName("Sun Tzu", NameType.SPELLING_VARIANT)));
        sunTzu.setFloruit(new HistoricalDate(-511, null, null, 30));

        Person unknown = new Person("Unknown sailor", Gender.UNKNOWN);

        Person olderSmith = new Person("John Smith", Gender.MALE);
        olderSmith.setBirth(new HistoricalDate(1700, null, null, 0));
        olderSmith.setDeath(new HistoricalDate(1750, null, null, 0));
        Person youngerSmith = new Person("John Smith", Gender.MALE);
        youngerSmith.setBirth(new HistoricalDate(1700, null, null, 0));
        youngerSmith.setDeath(new HistoricalDate(1760, null, null, 0));

        return List.of(caesar, bonny, cortes, sunTzu, unknown, olderSmith, youngerSmith);
    }

    /** Reads every file in a folder, keyed by file name. */
    private static Map<String, String> contents(Path folder) throws IOException {
        Map<String, String> contents = new TreeMap<>();
        try (Stream<Path> files = Files.list(folder)) {
            for (Path file : files.toList()) {
                contents.put(file.getFileName().toString(), Files.readString(file));
            }
        }
        return contents;
    }
}
