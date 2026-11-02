package se.pbt.curiositas.data;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import se.pbt.curiositas.date.HistoricalDate;
import se.pbt.curiositas.person.Gender;
import se.pbt.curiositas.person.Person;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

/**
 * Verifies how differences between the database and the data files are found and described,
 * since the description is all a developer has to find and fix the fault.
 */
class PersonDataCheckerTest {

    private static final UUID CAESAR_ID = UUID.fromString("9e18ac78-0789-4584-a817-65b2bad111c3");

    private final PersonExporter exporter = new PersonExporter();

    @TempDir
    private Path directory;

    /** Finds no differences right after an export. */
    @Test
    void findsNothingWhenFilesMatch() {
        exporter.export(List.of(caesar()), directory);

        assertThat(PersonDataChecker.compare(List.of(caesar()), directory).isEmpty()).isTrue();
    }

    /** Names a person without a history, with its id, under the file the person belongs in. */
    @Test
    void reportsPersonWithoutHistory() {
        DataDifferences differences = PersonDataChecker.withoutHistory(List.of(caesar()), Set.of(), directory);

        assertThat(differences.byFile()).containsExactly(entry(
                directory.resolve("julius-caesar-q1048.json").toString(),
                List.of("no history in the database: Julius Caesar (" + CAESAR_ID + ")")));
        assertThat(PersonDataChecker.withoutHistory(List.of(caesar()), Set.of(CAESAR_ID), directory).isEmpty()).isTrue();
    }

    /** Reports both kinds of problems together, both under the same file when they concern one person. */
    @Test
    void checksFilesAndHistoryTogether() {
        DataDifferences differences = PersonDataChecker.check(List.of(caesar()), Set.of(), directory);

        assertThat(differences.byFile()).containsExactly(entry(
                directory.resolve("julius-caesar-q1048.json").toString(),
                List.of("only in the database: Julius Caesar (" + CAESAR_ID + ")",
                        "no history in the database: Julius Caesar (" + CAESAR_ID + ")")));
        exporter.export(List.of(caesar()), directory);
        assertThat(PersonDataChecker.check(List.of(caesar()), Set.of(CAESAR_ID), directory).isEmpty()).isTrue();
    }

    /** Names a person that has no file, with its id so it can be found in the database. */
    @Test
    void reportsPersonOnlyInDatabase() {
        DataDifferences differences = PersonDataChecker.compare(List.of(caesar()), directory);

        assertThat(differences.byFile()).containsExactly(entry(
                directory.resolve("julius-caesar-q1048.json").toString(),
                List.of("only in the database: Julius Caesar (" + CAESAR_ID + ")")));
    }

    /** Treats a missing data folder as empty, so every stored person is reported. */
    @Test
    void treatsMissingFolderAsEmpty() {
        DataDifferences differences = PersonDataChecker.compare(List.of(caesar()), directory.resolve("missing"));

        assertThat(differences.byFile()).hasSize(1);
    }

    /** Reports a file that no person in the database matches. */
    @Test
    void reportsFileOnlyInFiles() throws IOException {
        Files.writeString(directory.resolve("sun-tzu.json"), "{}\n");

        DataDifferences differences = PersonDataChecker.compare(List.of(), directory);

        assertThat(differences.byFile().values()).containsExactly(List.of("only in the files"));
    }

    /**
     * Names each field that differs with both values, aligned for reading, and "(none)" for a
     * field that is missing on one side.
     */
    @Test
    void reportsEachDifferingFieldWithBothValues() throws IOException {
        exporter.export(List.of(caesar()), directory);
        Path file = directory.resolve("julius-caesar-q1048.json");
        Files.writeString(file, Files.readString(file)
                .replace(",\n    \"day\": 12", "")
                .replace("\"deathPlace\": \"Rome\"", "\"deathPlace\": \"Theatre of Pompey, Rome\""));

        DataDifferences differences = PersonDataChecker.compare(List.of(caesar()), directory);

        assertThat(differences.byFile().get(file.toString())).containsExactly(
                "birth.day    file: (none)   database: 12",
                "deathPlace   file: \"Theatre of Pompey, Rome\"   database: \"Rome\"");
    }

    /** Reports a file that is not valid JSON with the line and column of the fault. */
    @Test
    void reportsUnreadableFileWithPosition() throws IOException {
        exporter.export(List.of(caesar()), directory);
        Files.writeString(directory.resolve("julius-caesar-q1048.json"), "{\n  \"name\": \n}\n");

        DataDifferences differences = PersonDataChecker.compare(List.of(caesar()), directory);

        assertThat(differences.describe()).contains("cannot be read, line 3, column 1");
    }

    /** Reports a file whose data is right but whose formatting an export would change. */
    @Test
    void reportsFormattingOnlyDifference() throws IOException {
        exporter.export(List.of(caesar()), directory);
        Path file = directory.resolve("julius-caesar-q1048.json");
        Files.writeString(file, Files.readString(file).replace("\n", "\r\n"));

        DataDifferences differences = PersonDataChecker.compare(List.of(caesar()), directory);

        assertThat(differences.byFile().get(file.toString()))
                .containsExactly("same data, but not formatted the way an export writes it");
    }

    /** Creates Julius Caesar as stored, with a fixed id. */
    private static Person caesar() {
        Person caesar = new Person("Julius Caesar", Gender.MALE);
        caesar.setWikidataId("Q1048");
        caesar.setBirth(new HistoricalDate(-99, 7, 12, 0));
        caesar.setDeath(new HistoricalDate(-43, 3, 15, 0));
        caesar.setDeathPlace("Rome");
        ReflectionTestUtils.setField(caesar, "id", CAESAR_ID);
        return caesar;
    }
}
