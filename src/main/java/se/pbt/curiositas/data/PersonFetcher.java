package se.pbt.curiositas.data;

import jakarta.validation.Validator;
import org.springframework.stereotype.Component;
import se.pbt.curiositas.api.model.PersonInputDto;
import se.pbt.curiositas.person.InvalidPersonException;
import se.pbt.curiositas.person.PersonMapper;
import se.pbt.curiositas.wikidata.FetchedPerson;
import se.pbt.curiositas.wikidata.WikidataPersons;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Fetches the persons of every theme from Wikidata and writes them to the data folder, for review
 * as a git diff before they are imported. The database is never touched.
 *
 * <p>A fetched person replaces the file that has the same Wikidata id, whatever the file is called,
 * so the files of a theme always show what Wikidata says; corrections belong in Wikidata. Files of
 * persons outside the themes are left as they are.
 *
 * <p>Every person is checked by the same rules as the API before anything is written. If any person
 * breaks them, for example with a death before the birth, nothing is written.
 */
@Component
public class PersonFetcher {

    private final WikidataPersons wikidata;
    private final Validator validator;

    /**
     * Creates the fetcher.
     *
     * @param wikidata  reads persons from Wikidata
     * @param validator checks persons by the same rules as the API
     */
    public PersonFetcher(WikidataPersons wikidata, Validator validator) {
        this.wikidata = wikidata;
        this.validator = validator;
    }

    /**
     * Fetches the persons of every theme and writes their files.
     *
     * @param themesDirectory  the folder of theme files
     * @param personsDirectory the folder of person files; created if missing
     * @return the themes, what was written, and the notes for the review by file
     * @throws InvalidDataFilesException if a theme file or a fetched person breaks the rules
     * @throws UncheckedIOException      if a file cannot be read or written
     */
    public Result fetch(Path themesDirectory, Path personsDirectory) {
        List<Themes.Theme> themes = Themes.readAll(themesDirectory);
        List<String> ids = Themes.wikidataIds(themes);
        List<String> names = themes.stream().map(Themes.Theme::name).toList();
        if (ids.isEmpty()) {
            return new Result(names, 0, 0, new TreeMap<>());
        }
        List<FetchedPerson> fetched = wikidata.fetch(ids);
        Map<String, String> contents = checked(fetched);
        try {
            Files.createDirectories(personsDirectory);
            Map<String, Path> existing = filesByWikidataId(personsDirectory);
            int written = 0;
            int unchanged = 0;
            TreeMap<String, List<String>> notes = new TreeMap<>();
            for (FetchedPerson person : fetched) {
                String id = person.person().getWikidataId();
                Path path = existing.containsKey(id) ? existing.get(id) : freePath(personsDirectory, person.person());
                String content = contents.get(id);
                if (Files.exists(path) && Files.readString(path, StandardCharsets.UTF_8).equals(content)) {
                    unchanged++;
                } else {
                    Files.writeString(path, content, StandardCharsets.UTF_8);
                    written++;
                }
                existing.put(id, path);
                if (!person.notes().isEmpty()) {
                    notes.put(path.getFileName().toString(), person.notes());
                }
            }
            return new Result(names, written, unchanged, notes);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write the fetched persons to " + personsDirectory, e);
        }
    }

    /**
     * Checks every person by the API's rules and returns the content of each file, by Wikidata id.
     * The content goes through the same conversion as an export, so a fetched file is exactly what
     * the database will give back after an import.
     */
    private Map<String, String> checked(List<FetchedPerson> fetched) {
        Map<String, String> contents = new LinkedHashMap<>();
        TreeMap<String, List<String>> problems = new TreeMap<>();
        for (FetchedPerson person : fetched) {
            PersonInputDto input = person.person();
            String label = input.getName() + " (" + input.getWikidataId() + ")";
            List<String> personProblems = new ArrayList<>(validator.validate(input).stream()
                    .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                    .sorted()
                    .toList());
            if (personProblems.isEmpty()) {
                try {
                    contents.put(input.getWikidataId(),
                            PersonFileFormat.toJson(PersonMapper.toInput(PersonMapper.toNewPerson(input))));
                } catch (InvalidPersonException impossible) {
                    impossible.getViolations().forEach(violation ->
                            personProblems.add(violation.field() + ": " + violation.message()));
                }
            }
            if (!personProblems.isEmpty()) {
                problems.put(label, personProblems);
            }
        }
        if (!problems.isEmpty()) {
            throw new InvalidDataFilesException(problems);
        }
        return contents;
    }

    /** Finds the file of every person with a Wikidata id; files that cannot be read are skipped. */
    private static Map<String, Path> filesByWikidataId(Path directory) throws IOException {
        Map<String, Path> files = new HashMap<>();
        for (Path path : PersonExporter.jsonFilesIn(directory)) {
            try {
                JsonNode id = PersonFileFormat.toTree(Files.readString(path, StandardCharsets.UTF_8)).path("wikidataId");
                if (id.isString()) {
                    files.put(id.asString(), path);
                }
            } catch (JacksonException | IOException unreadable) {
                // The import reports broken files; a fetch only needs to find the readable ones.
            }
        }
        return files;
    }

    /** Returns the path for a new person's file, with a number added if the name is taken. */
    private static Path freePath(Path directory, PersonInputDto person) {
        String base = PersonFileFormat.baseName(person);
        Path path = directory.resolve(base + ".json");
        for (int n = 2; Files.exists(path); n++) {
            path = directory.resolve(base + "-" + n + ".json");
        }
        return path;
    }

    /**
     * What a fetch did.
     *
     * @param themes    the names of the themes fetched
     * @param written   files created or updated
     * @param unchanged files that already had the fetched content
     * @param notes     what the review should look at, by file name
     */
    public record Result(List<String> themes, int written, int unchanged, TreeMap<String, List<String>> notes) {
    }
}
