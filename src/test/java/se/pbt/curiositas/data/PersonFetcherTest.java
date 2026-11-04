package se.pbt.curiositas.data;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import se.pbt.curiositas.wikidata.WikidataClient;
import se.pbt.curiositas.wikidata.WikidataFixtures;
import se.pbt.curiositas.wikidata.WikidataPersons;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Verifies that fetching writes the persons of the themes to the data folder exactly as an export
 * would write them, replaces only the files of those persons, and writes nothing when a theme or a
 * person breaks the rules. Wikidata is replaced by real items saved from it.
 */
class PersonFetcherTest {

    private static final String CAESAR_FILE = """
            {
              "name": "Julius Caesar",
              "wikidataId": "Q1048",
              "birth": {
                "year": -99,
                "month": 7
              },
              "birthPlace": "Rome",
              "birthCountry": "IT",
              "death": {
                "year": -43,
                "month": 3,
                "day": 15
              },
              "deathPlace": "Theatre of Pompey",
              "gender": "MALE"
            }
            """;

    private static final String TEACH_FILE = """
            {
              "name": "Edward Teach",
              "wikidataId": "Q213518",
              "gender": "MALE"
            }
            """;

    @TempDir
    private Path data;

    private Path themes;
    private Path persons;
    private MockRestServiceServer wikidata;
    private ValidatorFactory validation;
    private PersonFetcher fetcher;

    /** Puts a fake Wikidata behind a fresh fetcher, with empty folders for themes and persons. */
    @BeforeEach
    void setUp() throws IOException {
        themes = Files.createDirectories(data.resolve("themes"));
        persons = data.resolve("persons");
        RestClient.Builder builder = RestClient.builder();
        wikidata = MockRestServiceServer.bindTo(builder).build();
        validation = Validation.buildDefaultValidatorFactory();
        fetcher = new PersonFetcher(new WikidataPersons(new WikidataClient(builder,
                URI.create("https://wikidata.test/w/api.php"), "Curiositas/test")), validation.getValidator());
    }

    /** Closes the validation. */
    @AfterEach
    void tearDown() {
        validation.close();
    }

    /**
     * Writes a new file for each person in a theme, in the format of an export, and passes on the
     * notes for the review by file.
     */
    @Test
    void writesFilesOfThemePersons() throws IOException {
        writeTheme("roman-empire.json", "The Roman Empire", "Q1048");
        expectCaesar();

        PersonFetcher.Result result = fetcher.fetch(themes, persons);

        assertThat(read("julius-caesar-q1048.json")).isEqualTo(CAESAR_FILE);
        assertThat(result.themes()).containsExactly("The Roman Empire");
        assertThat(result.written()).isEqualTo(1);
        assertThat(result.notes()).containsOnlyKeys("julius-caesar-q1048.json");
        assertThat(result.notes().get("julius-caesar-q1048.json").getFirst()).startsWith("Sources disagree on the birth");
        wikidata.verify();
    }

    /**
     * Replaces the file with the same Wikidata id under its own name, and leaves the files of
     * persons outside the themes alone.
     */
    @Test
    void replacesOnlyFilesOfThemePersons() throws IOException {
        Files.createDirectories(persons);
        Files.writeString(persons.resolve("caesar.json"),
                "{ \"name\": \"Gaius Julius Caesar\", \"wikidataId\": \"Q1048\", \"gender\": \"MALE\" }\n");
        Files.writeString(persons.resolve("edward-teach-q213518.json"), TEACH_FILE);
        writeTheme("roman-empire.json", "The Roman Empire", "Q1048");
        expectCaesar();

        fetcher.fetch(themes, persons);

        assertThat(read("caesar.json")).isEqualTo(CAESAR_FILE);
        assertThat(read("edward-teach-q213518.json")).isEqualTo(TEACH_FILE);
        try (var files = Files.list(persons)) {
            assertThat(files.map(path -> path.getFileName().toString()))
                    .containsExactlyInAnyOrder("caesar.json", "edward-teach-q213518.json");
        }
    }

    /** Leaves a file that already has the fetched content as it is. */
    @Test
    void countsUnchangedFiles() throws IOException {
        Files.createDirectories(persons);
        Files.writeString(persons.resolve("julius-caesar-q1048.json"), CAESAR_FILE);
        writeTheme("roman-empire.json", "The Roman Empire", "Q1048");
        expectCaesar();

        PersonFetcher.Result result = fetcher.fetch(themes, persons);

        assertThat(result.written()).isZero();
        assertThat(result.unchanged()).isEqualTo(1);
    }

    /** Fetches a person who belongs to several themes once, together with the other persons. */
    @Test
    void fetchesEachPersonOnce() throws IOException {
        writeTheme("piracy.json", "The Golden Age of Piracy", "Q231549", "Q1048");
        writeTheme("roman-empire.json", "The Roman Empire", "Q1048");
        wikidata.expect(anything()).andRespond(WikidataFixtures.answer("Q231549", "Q1048"));
        wikidata.expect(anything()).andRespond(WikidataFixtures.answer("Q840681", "Q220", "Q944814"));
        wikidata.expect(anything()).andRespond(WikidataFixtures.answer("Q27", "Q38", "Q237"));

        PersonFetcher.Result result = fetcher.fetch(themes, persons);

        assertThat(result.themes()).containsExactly("The Golden Age of Piracy", "The Roman Empire");
        assertThat(result.written()).isEqualTo(2);
        assertThat(read("anne-bonny-q231549.json")).contains("\"birthPlace\": \"possibly Kinsale\"");
        wikidata.verify();
    }

    /** Fetches nothing when there are no themes. */
    @Test
    void fetchesNothingWithoutThemes() {
        PersonFetcher.Result result = fetcher.fetch(data.resolve("no-themes"), persons);

        assertThat(result.themes()).isEmpty();
        assertThat(result.written()).isZero();
        wikidata.verify();
    }

    /**
     * Checks every theme before asking Wikidata, and lists each problem with its file and field,
     * so nothing is fetched until every theme is right.
     */
    @Test
    void reportsEveryProblemInThemes() throws IOException {
        Files.writeString(themes.resolve("a-broken.json"), "{ \"name\": ");
        Files.writeString(themes.resolve("b-misspelled.json"), "{ \"name\": \"Rome\", \"person\": [] }");
        Files.writeString(themes.resolve("c-wrong-ids.json"), """
                { "name": " ", "persons": [
                  { "wikidataId": "Q1048" }, { "wikidataId": "Q1048" }, { "wikidataId": "P31" } ] }""");
        writeTheme("d-fine.json", "The Roman Empire", "Q1048");

        assertThatThrownBy(() -> fetcher.fetch(themes, persons))
                .isInstanceOfSatisfying(InvalidDataFilesException.class, invalid ->
                        assertThat(invalid.getProblemsByFile()).satisfies(problems -> {
                            assertThat(problems).containsOnlyKeys(
                                    "themes/a-broken.json", "themes/b-misspelled.json", "themes/c-wrong-ids.json");
                            assertThat(problems.get("themes/b-misspelled.json"))
                                    .containsExactly("person: is not a known field");
                            assertThat(problems.get("themes/c-wrong-ids.json")).containsExactly(
                                    "name: must not be blank",
                                    "persons[1].wikidataId: Q1048 is already in the list",
                                    "persons[2].wikidataId: must be a Wikidata item id, such as Q1048");
                        }));
        assertThat(persons).doesNotExist();
        wikidata.verify();
    }

    /** Writes nothing when a fetched person breaks the API's rules, such as dying before birth. */
    @Test
    void writesNothingWhenPersonBreaksRules() throws IOException {
        writeTheme("roman-empire.json", "The Roman Empire", "Q1048", "Q2");
        wikidata.expect(anything()).andRespond(withSuccess(WikidataFixtures.entities(Map.of(
                "Q1048", WikidataFixtures.item("Q1048"),
                "Q2", """
                        { "id": "Q2", "labels": { "en": { "language": "en", "value": "Backwards" } }, "claims": {
                          "P569": [ { "rank": "normal", "mainsnak": { "snaktype": "value", "property": "P569",
                            "datavalue": { "type": "time", "value": { "time": "+1700-00-00T00:00:00Z", "precision": 9,
                              "calendarmodel": "http://www.wikidata.org/entity/Q1985727" } } } } ],
                          "P570": [ { "rank": "normal", "mainsnak": { "snaktype": "value", "property": "P570",
                            "datavalue": { "type": "time", "value": { "time": "+1690-00-00T00:00:00Z", "precision": 9,
                              "calendarmodel": "http://www.wikidata.org/entity/Q1985727" } } } } ] } }""")),
                MediaType.APPLICATION_JSON));
        wikidata.expect(anything()).andRespond(WikidataFixtures.answer("Q220", "Q944814"));
        wikidata.expect(anything()).andRespond(WikidataFixtures.answer("Q38", "Q237"));

        assertThatThrownBy(() -> fetcher.fetch(themes, persons))
                .isInstanceOfSatisfying(InvalidDataFilesException.class, invalid ->
                        assertThat(invalid.getProblemsByFile()).containsOnlyKeys("Backwards (Q2)"));
        assertThat(persons).doesNotExist();
    }

    /** Expects the three requests that read Julius Caesar, his places and their countries. */
    private void expectCaesar() {
        wikidata.expect(anything()).andRespond(WikidataFixtures.answer("Q1048"));
        wikidata.expect(anything()).andRespond(WikidataFixtures.answer("Q220", "Q944814"));
        wikidata.expect(anything()).andRespond(WikidataFixtures.answer("Q38", "Q237"));
    }

    /** Writes a theme with the given persons, named only by their ids. */
    private void writeTheme(String file, String name, String... ids) throws IOException {
        StringBuilder json = new StringBuilder("{ \"name\": \"" + name + "\", \"persons\": [");
        for (int i = 0; i < ids.length; i++) {
            json.append(i == 0 ? "" : ", ").append("{ \"wikidataId\": \"").append(ids[i]).append("\" }");
        }
        Files.writeString(themes.resolve(file), json.append("] }").toString());
    }

    /** Reads a person file. */
    private String read(String file) throws IOException {
        return Files.readString(persons.resolve(file), StandardCharsets.UTF_8);
    }
}
