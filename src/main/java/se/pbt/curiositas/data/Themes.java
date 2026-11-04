package se.pbt.curiositas.data;

import se.pbt.curiositas.json.JsonFieldPath;
import tools.jackson.core.JacksonException;
import tools.jackson.core.exc.StreamReadException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.exc.UnrecognizedPropertyException;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Reads the themes in {@code data/themes}: one file per theme, listing the persons chosen for it
 * by their Wikidata ids. The list is where the curation happens; Wikidata only supplies the facts.
 *
 * <pre>{@code
 * {
 *   "name": "The Roman Empire",
 *   "persons": [
 *     { "wikidataId": "Q1048", "name": "Julius Caesar" }
 *   ]
 * }
 * }</pre>
 *
 * <p>The name of each person is there only so the list can be read and reviewed; the name in the
 * person's file comes from Wikidata.
 */
final class Themes {

    private static final Pattern ITEM_ID = Pattern.compile("^Q[1-9]\\d*$");

    private static final JsonMapper JSON = JsonMapper.builder()
            // A misspelled field, such as "wikidataID", must be reported instead of silently dropped.
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    /** Not instantiable; all methods are static. */
    private Themes() {
    }

    /**
     * Reads every theme, checking every file before any is used.
     *
     * @param directory the folder of theme files; a missing folder holds no themes
     * @return the themes, ordered by file name
     * @throws InvalidDataFilesException if any file cannot be read or breaks the rules, listing
     *                                   every problem by file
     * @throws UncheckedIOException      if the folder cannot be listed
     */
    static List<Theme> readAll(Path directory) {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        List<Theme> themes = new ArrayList<>();
        TreeMap<String, List<String>> problems = new TreeMap<>();
        for (Path path : jsonFilesIn(directory)) {
            String name = path.getFileName().toString();
            List<String> fileProblems = new ArrayList<>();
            Theme theme = read(path, fileProblems);
            if (fileProblems.isEmpty()) {
                themes.add(theme);
            } else {
                problems.put("themes/" + name, fileProblems);
            }
        }
        if (!problems.isEmpty()) {
            throw new InvalidDataFilesException(problems);
        }
        return themes;
    }

    /**
     * Returns the Wikidata ids of every person in the themes, each once, in the order they first
     * appear. A person may belong to several themes.
     *
     * @param themes the themes
     * @return the ids
     */
    static List<String> wikidataIds(List<Theme> themes) {
        Set<String> ids = new LinkedHashSet<>();
        themes.forEach(theme -> theme.persons().forEach(person -> ids.add(person.wikidataId())));
        return List.copyOf(ids);
    }

    /** Reads one theme file and checks its rules; problems are added to the list instead of thrown. */
    private static Theme read(Path path, List<String> problems) {
        Theme theme;
        try {
            theme = JSON.readValue(Files.readString(path, StandardCharsets.UTF_8), Theme.class);
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
            problems.add(JsonFieldPath.of(wrongValue) + ": has a value of the wrong type");
            return null;
        }
        if (theme.name() == null || theme.name().isBlank()) {
            problems.add("name: must not be blank");
        }
        if (theme.persons() == null || theme.persons().isEmpty()) {
            problems.add("persons: must list at least one person");
            return theme;
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < theme.persons().size(); i++) {
            Person person = theme.persons().get(i);
            String field = "persons[" + i + "]";
            if (person == null || person.wikidataId() == null || !ITEM_ID.matcher(person.wikidataId()).matches()) {
                problems.add(field + ".wikidataId: must be a Wikidata item id, such as Q1048");
            } else if (!seen.add(person.wikidataId())) {
                problems.add(field + ".wikidataId: " + person.wikidataId() + " is already in the list");
            }
        }
        return theme;
    }

    /** Lists the JSON files directly in the folder, ordered by name. */
    private static List<Path> jsonFilesIn(Path directory) {
        try (Stream<Path> paths = Files.list(directory)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .filter(Files::isRegularFile)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not list the themes in " + directory, e);
        }
    }

    /**
     * A theme: a time and a place, with the persons chosen for it.
     *
     * @param name    the theme's name, for example "The Roman Empire"
     * @param persons the persons, in the order they are listed
     */
    record Theme(String name, List<Person> persons) {
    }

    /**
     * A person chosen for a theme.
     *
     * @param wikidataId the person's Wikidata id, for example {@code Q1048}
     * @param name       the person's name, only to make the list readable
     */
    record Person(String wikidataId, String name) {
    }
}
