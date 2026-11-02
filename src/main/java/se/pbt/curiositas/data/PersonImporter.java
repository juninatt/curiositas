package se.pbt.curiositas.data;

import jakarta.validation.Validator;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import se.pbt.curiositas.api.model.HistoricalDateInputDto;
import se.pbt.curiositas.api.model.PersonInputDto;
import se.pbt.curiositas.json.JsonFieldPath;
import se.pbt.curiositas.person.ChangeSource;
import se.pbt.curiositas.person.InvalidPersonException;
import se.pbt.curiositas.person.Person;
import se.pbt.curiositas.person.PersonHistory;
import se.pbt.curiositas.person.PersonMapper;
import se.pbt.curiositas.person.PersonNames;
import se.pbt.curiositas.person.PersonRepository;
import tools.jackson.core.JacksonException;
import tools.jackson.core.exc.StreamReadException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Makes the database mirror the data files, which are the source of truth: persons with a file
 * are created or updated, persons without one are deleted.
 *
 * <p>Persons are matched to their files by the same identity as the duplicate check uses, so a
 * person keeps its id, and with it every link to it, as long as it is recognisably the same person.
 * Every person created, changed or deleted gets a revision with the source {@code IMPORT}; an
 * unchanged person gets none, unless it has no history yet. Such a person was stored before the
 * history existed, and the import records it as created, so every person has a history.
 */
@Component
public class PersonImporter {

    private final PersonRepository repository;
    private final PersonHistory history;
    private final Validator validator;

    /**
     * Creates the importer.
     *
     * @param repository where persons are stored
     * @param history    records every change, in the same transaction as the import
     * @param validator  checks each file against the same rules as the API's schema
     */
    public PersonImporter(PersonRepository repository, PersonHistory history, Validator validator) {
        this.repository = repository;
        this.history = history;
        this.validator = validator;
    }

    /**
     * Reads every file, and only if all of them are valid, changes the database to match them.
     * Either everything is imported or nothing changes.
     *
     * @param directory the folder with one JSON file per person
     * @return how many persons were created, updated, left unchanged and deleted
     * @throws InvalidDataFilesException if any file is invalid, listing every problem in every file
     * @throws UncheckedIOException      if the folder is missing or cannot be read; a missing folder
     *                                   is an error rather than "no persons", so a wrong path can
     *                                   never empty the database
     */
    @Transactional
    public Result importFrom(Path directory) {
        List<PersonFile> files = readAll(directory);

        List<Person> stored = repository.findAll();
        Set<UUID> withHistory = history.personsWithHistory();
        Map<PersonFile, Person> matches = match(files, stored);
        Set<Person> matched = Set.copyOf(matches.values());
        List<Person> deleted = stored.stream().filter(person -> !matched.contains(person)).toList();
        // Deleted first, so a new or changed person can never clash with one that is about to go.
        deleted.forEach(person -> history.deleted(person, ChangeSource.IMPORT));
        repository.deleteAll(deleted);
        repository.flush();

        List<Person> created = new ArrayList<>();
        List<Person> updated = new ArrayList<>();
        int unchanged = 0;
        for (PersonFile file : files) {
            Person existing = matches.get(file);
            if (existing == null) {
                created.add(repository.save(PersonMapper.toNewPerson(file.input())));
            } else if (file.normalisedContent().equals(contentOf(existing))) {
                unchanged++;
            } else {
                PersonMapper.copyInput(file.input(), existing);
                updated.add(existing);
            }
        }
        List<Person> withoutHistory = stored.stream()
                .filter(matched::contains)
                .filter(person -> !withHistory.contains(person.getId()))
                .toList();
        // Recorded after writing, so each revision has the time and data the database stored.
        repository.flush();
        created.forEach(person -> history.created(person, ChangeSource.IMPORT));
        updated.stream()
                .filter(person -> withHistory.contains(person.getId()))
                .forEach(person -> history.updated(person, ChangeSource.IMPORT));
        withoutHistory.forEach(person -> history.created(person, ChangeSource.IMPORT));
        return new Result(created.size(), updated.size(), unchanged, deleted.size(), withoutHistory.size());
    }

    /** Reads and checks every file, collecting all problems before reporting any. */
    private List<PersonFile> readAll(Path directory) {
        if (!Files.isDirectory(directory)) {
            throw new UncheckedIOException("The data folder " + directory + " does not exist",
                    new NoSuchFileException(directory.toString()));
        }
        Map<String, List<String>> problems = new TreeMap<>();
        List<PersonFile> files = new ArrayList<>();
        for (Path path : jsonFilesIn(directory)) {
            String name = path.toString();
            List<String> fileProblems = new ArrayList<>();
            PersonFile file = read(path, name, fileProblems);
            if (fileProblems.isEmpty()) {
                files.add(file);
            } else {
                problems.put(name, fileProblems);
            }
        }
        reportDuplicates(files, problems);
        if (!problems.isEmpty()) {
            throw new InvalidDataFilesException(problems);
        }
        return files;
    }

    /**
     * Reads one file and checks it against the schema and the rules for possible data. Problems
     * are added to the list instead of thrown, so every file is checked.
     */
    private PersonFile read(Path path, String name, List<String> problems) {
        PersonInputDto input;
        try {
            input = PersonFileFormat.fromJson(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException unreadable) {
            problems.add("cannot be read as UTF-8 text: " + unreadable.getMessage());
            return null;
        } catch (StreamReadException malformed) {
            problems.add("line " + malformed.getLocation().getLineNr() + ", column "
                    + malformed.getLocation().getColumnNr() + ": " + malformed.getOriginalMessage());
            return null;
        } catch (UnrecognizedPropertyException unknownField) {
            problems.add(JsonFieldPath.of(unknownField) + ": is not a known field");
            return null;
        } catch (JacksonException wrongValue) {
            problems.add(JsonFieldPath.of(wrongValue) + ": has a value of the wrong type or outside the allowed values");
            return null;
        }

        validator.validate(input).stream()
                .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                .sorted()
                .forEach(problems::add);
        if (!problems.isEmpty()) {
            return null;
        }
        try {
            Person person = PersonMapper.toNewPerson(input);
            return new PersonFile(name, input, contentOf(person));
        } catch (InvalidPersonException impossible) {
            impossible.getViolations().forEach(violation -> problems.add(violation.field() + ": " + violation.message()));
            return null;
        }
    }

    /**
     * Reports files that describe the same person, by the same rules as the API's duplicate check,
     * since the database could not hold both.
     */
    private static void reportDuplicates(List<PersonFile> files, Map<String, List<String>> problems) {
        Map<String, PersonFile> byWikidataId = new HashMap<>();
        Map<String, PersonFile> byLife = new HashMap<>();
        for (PersonFile file : files) {
            String wikidataId = file.input().getWikidataId();
            PersonFile same = wikidataId != null ? byWikidataId.get(wikidataId) : byLife.get(lifeKey(file.input()));
            if (same != null) {
                problems.computeIfAbsent(file.name(), name -> new ArrayList<>())
                        .add("describes the same person as " + same.name());
            }
            if (wikidataId != null) {
                byWikidataId.putIfAbsent(wikidataId, file);
            }
            byLife.putIfAbsent(lifeKey(file.input()), file);
        }
    }

    /**
     * Pairs each file with the stored person it describes: first by Wikidata id, then by name and
     * life, so a person keeps its id even when a Wikidata id is added to its file.
     */
    private static Map<PersonFile, Person> match(List<PersonFile> files, List<Person> stored) {
        Map<String, Person> byWikidataId = new HashMap<>();
        Map<String, List<Person>> byLife = new HashMap<>();
        for (Person person : stored) {
            PersonInputDto input = PersonMapper.toInput(person);
            if (input.getWikidataId() != null) {
                byWikidataId.put(input.getWikidataId(), person);
            }
            byLife.computeIfAbsent(lifeKey(input), key -> new ArrayList<>()).add(person);
        }

        Map<PersonFile, Person> matches = new HashMap<>();
        Set<Person> taken = new HashSet<>();
        for (PersonFile file : files) {
            String wikidataId = file.input().getWikidataId();
            Person match = wikidataId != null ? byWikidataId.get(wikidataId) : null;
            if (match == null) {
                match = byLife.getOrDefault(lifeKey(file.input()), List.of()).stream()
                        .filter(candidate -> !taken.contains(candidate))
                        .filter(candidate -> candidate.getWikidataId() == null || wikidataId == null)
                        .findFirst()
                        .orElse(null);
            }
            if (match != null && taken.add(match)) {
                matches.put(file, match);
            }
        }
        return matches;
    }

    /** Identifies a person by normalised name, birth, death and birth country. */
    private static String lifeKey(PersonInputDto input) {
        return String.join("|", PersonNames.normalize(input.getName()), dateKey(input.getBirth()),
                dateKey(input.getDeath()), Objects.toString(input.getBirthCountry(), "-"));
    }

    /** Identifies a date by all its parts, treating a missing uncertainty as 0. */
    private static String dateKey(HistoricalDateInputDto date) {
        if (date == null) {
            return "-";
        }
        return date.getYear() + "/" + date.getMonth() + "/" + date.getDay() + "/"
                + Objects.requireNonNullElse(date.getUncertaintyYears(), 0);
    }

    /** Returns a person as its file would read, for comparing stored data with files. */
    private static String contentOf(Person person) {
        return PersonFileFormat.toJson(PersonMapper.toInput(person));
    }

    /** Lists the JSON files in the folder in alphabetical order. */
    private static List<Path> jsonFilesIn(Path directory) {
        try (Stream<Path> paths = Files.list(directory)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .filter(Files::isRegularFile)
                    .sorted()
                    .toList();
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not read the data folder " + directory, exception);
        }
    }

    /**
     * One valid data file.
     *
     * @param name              the file's path, for messages
     * @param input             the person it describes
     * @param normalisedContent the person as an export would write it, for detecting changes
     */
    private record PersonFile(String name, PersonInputDto input, String normalisedContent) {
    }

    /**
     * What an import changed.
     *
     * @param created        persons that had a file but were not in the database
     * @param updated        persons whose data differed from their file
     * @param unchanged      persons that already matched their file
     * @param deleted        persons that had no file
     * @param historyStarted persons already stored without a history, which now have their first revision
     */
    public record Result(int created, int updated, int unchanged, int deleted, int historyStarted) {
    }
}
