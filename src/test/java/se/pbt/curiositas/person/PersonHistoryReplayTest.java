package se.pbt.curiositas.person;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import se.pbt.curiositas.TestcontainersConfiguration;
import se.pbt.curiositas.api.model.PersonInputDto;
import se.pbt.curiositas.data.InvalidDataFilesException;
import se.pbt.curiositas.data.PersonExporter;
import se.pbt.curiositas.data.PersonImporter;
import se.pbt.curiositas.date.HistoricalDate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the promise the history makes: replaying every revision from the start gives exactly the
 * persons in the database, whatever changes were made, in whatever order, and from whichever
 * source. A client that keeps in sync through the history depends on this.
 *
 * <p>Makes hundreds of random changes through the service and through imports, including changes
 * that are rejected, and checks the promise after every one, so a failure names the seed and the
 * step that broke it. A seed makes nearly the same changes every run; only persons stored at the
 * very same moment can be picked in another order.
 *
 * <p>Runs without the usual test transaction, so every change is committed as in the application.
 */
// Thousands of queries; printing them would bury the rest of the build output.
@DataJpaTest(showSql = false)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(ValidationAutoConfiguration.class)
@Import({TestcontainersConfiguration.class, PersonService.class, PersonHistory.class, PersonImporter.class,
        PersonExporter.class})
class PersonHistoryReplayTest {

    private static final int STEPS = 200;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** A fixed order, so a seed picks the same persons as far as creation times allow. */
    private static final Sort IN_ORDER_OF_CREATION = Sort.by("createdAt", "name", "birthPlace");

    /** Few names, so that duplicates and repeated names happen often. */
    private static final List<String> NAMES = List.of("Edward Teach", "Anne Bonny", "Mary Read", "Julius Caesar",
            "Sun Tzu", "Hypatia");

    private static final List<String> PLACES = List.of("Bristol", "Kinsale", "London", "Rome", "Alexandria");

    @Autowired
    private PersonService service;

    @Autowired
    private PersonImporter importer;

    @Autowired
    private PersonExporter exporter;

    @Autowired
    private PersonRepository persons;

    @Autowired
    private PersonRevisionRepository revisions;

    private Random random;

    private int step;

    /** Removes everything a run stored, since nothing is rolled back. */
    @AfterEach
    void cleanUp() {
        persons.deleteAll();
        revisions.deleteAll();
    }

    /**
     * Makes random changes and checks after each one that replaying the history gives the stored
     * persons, and that every person's revisions form a valid sequence.
     *
     * @param seed decides which changes are made
     */
    @ParameterizedTest
    @ValueSource(longs = {1, 2, 3})
    void replayingTheHistoryAlwaysGivesTheCurrentData(long seed) throws IOException {
        random = new Random(seed);
        Path files = Files.createTempDirectory("curiositas-replay");

        for (step = 1; step <= STEPS; step++) {
            String change = makeRandomChange(files);

            assertThat(replay()).as("seed %d, step %d (%s): replayed history", seed, step, change)
                    .isEqualTo(stored());
            assertEveryHistoryIsValid(seed, change);
        }
        assertThat(revisions.count()).as("seed %d: changes recorded", seed).isGreaterThan(STEPS / 2);
    }

    /** Makes one random change, which may be rejected, and returns what it was. */
    private String makeRandomChange(Path files) throws IOException {
        List<Person> all = persons.findAll(IN_ORDER_OF_CREATION);
        int choice = all.isEmpty() ? 0 : random.nextInt(10);
        String change = switch (choice) {
            case 0, 1 -> "create";
            case 2, 3 -> "replace";
            case 4 -> "replace with the same data";
            case 5 -> "replace an outdated version";
            case 6 -> "delete";
            default -> "import changed files";
        };
        try {
            switch (choice) {
                case 0, 1 -> service.create(randomPerson());
                case 2, 3 -> {
                    Person person = any(all);
                    service.replace(person.getId(), person.getVersion(), this::changeRandomly);
                }
                case 4 -> {
                    Person person = any(all);
                    service.replace(person.getId(), person.getVersion(), same -> same.setName(same.getName()));
                }
                case 5 -> {
                    Person person = any(all);
                    service.replace(person.getId(), person.getVersion() + 1, this::changeRandomly);
                }
                case 6 -> service.delete(any(all).getId());
                default -> importChangedFiles(files, all);
            }
            return change;
        } catch (DuplicatePersonException | PersonChangedException | InvalidPersonException
                 | InvalidDataFilesException rejected) {
            return change + ", rejected: " + rejected.getMessage();
        }
    }

    /**
     * Exports the stored persons, changes the files the way an edit in git could, and imports
     * them: one file changed, one removed and one added, each only sometimes.
     */
    private void importChangedFiles(Path files, List<Person> all) throws IOException {
        exporter.export(all, files);
        List<Path> paths = jsonFilesIn(files);
        if (!paths.isEmpty() && random.nextBoolean()) {
            Path path = any(paths);
            PersonInputDto input = JSON.readValue(Files.readString(path), PersonInputDto.class);
            input.setBirthPlace(any(PLACES));
            Files.writeString(path, JSON.writeValueAsString(input));
        }
        if (!paths.isEmpty() && random.nextInt(4) == 0) {
            Files.delete(any(paths));
        }
        if (random.nextBoolean()) {
            PersonInputDto added = PersonMapper.toInput(randomPerson());
            Files.writeString(files.resolve("added-" + step + ".json"), JSON.writeValueAsString(added));
        }
        importer.importFrom(files);
    }

    /** Replays every revision in order: creations and updates set the person, deletions remove it. */
    private Map<UUID, JsonNode> replay() {
        Map<UUID, JsonNode> replayed = new HashMap<>();
        revisions.findAll().stream()
                .sorted(Comparator.comparingLong(PersonRevision::getSequenceNumber))
                .forEach(revision -> {
                    if (revision.getChangeType() == ChangeType.DELETED) {
                        replayed.remove(revision.getPersonId());
                    } else {
                        replayed.put(revision.getPersonId(), JSON.readTree(revision.getSnapshot()));
                    }
                });
        return replayed;
    }

    /** Returns the stored persons in the form snapshots have, converted independently of the history. */
    private Map<UUID, JsonNode> stored() {
        return persons.findAll().stream()
                .collect(Collectors.toMap(Person::getId, person -> JSON.valueToTree(PersonMapper.toInput(person))));
    }

    /**
     * Checks that every person's revisions are numbered 1, 2, 3 and so on in the order of their
     * sequence numbers, start with its creation, and end with its deletion if they contain one.
     */
    private void assertEveryHistoryIsValid(long seed, String change) {
        Map<UUID, List<PersonRevision>> byPerson = revisions.findAll().stream()
                .sorted(Comparator.comparingLong(PersonRevision::getSequenceNumber))
                .collect(Collectors.groupingBy(PersonRevision::getPersonId));
        byPerson.forEach((id, history) -> {
            String context = "seed " + seed + ", step " + step + " (" + change + "), person " + id;
            List<Integer> numbers = history.stream().map(PersonRevision::getRevision).toList();
            List<Integer> expected = new ArrayList<>();
            for (int number = 1; number <= history.size(); number++) {
                expected.add(number);
            }
            assertThat(numbers).as(context + ": revision numbers").isEqualTo(expected);
            assertThat(history.getFirst().getChangeType()).as(context + ": first change").isEqualTo(ChangeType.CREATED);
            assertThat(history.subList(0, history.size() - 1)).as(context + ": changes before the last")
                    .noneMatch(revision -> revision.getChangeType() == ChangeType.DELETED);
        });
    }

    /** Creates a person with a random name and life, sometimes with a Wikidata id. */
    private Person randomPerson() {
        Person person = new Person(any(NAMES), random.nextBoolean() ? Gender.MALE : Gender.FEMALE);
        person.setBirth(new HistoricalDate(1600 + random.nextInt(20), null, null, random.nextInt(3)));
        if (random.nextInt(3) == 0) {
            person.setWikidataId("Q" + (1 + random.nextInt(30)));
        }
        return person;
    }

    /** Changes one random field of the person. */
    private void changeRandomly(Person person) {
        switch (random.nextInt(4)) {
            case 0 -> person.setName(any(NAMES));
            case 1 -> person.setBirthPlace(any(PLACES));
            case 2 -> person.setDeath(new HistoricalDate(1650 + random.nextInt(20), null, null, 0));
            default -> person.setAlsoKnownAs(List.of(new AlternativeName(any(NAMES), NameType.NICKNAME)));
        }
    }

    /** Picks a random element. */
    private <T> T any(List<T> list) {
        return list.get(random.nextInt(list.size()));
    }

    /** Lists the JSON files in the folder, in a fixed order so a seed always picks the same ones. */
    private static List<Path> jsonFilesIn(Path directory) {
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(file -> file.toString().endsWith(".json")).sorted().toList();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
