package se.pbt.curiositas.data;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.nio.file.Path;

/**
 * Where the JSON files live that are the source of truth for the data. The database mirrors them.
 *
 * @param directory the data folder, "data" in the project unless configured otherwise
 */
@ConfigurationProperties("curiositas.data")
public record DataProperties(@DefaultValue("data") Path directory) {

    /** Returns the folder holding one JSON file per person. */
    public Path personsDirectory() {
        return directory.resolve("persons");
    }

    /** Returns the folder holding one JSON file per theme, listing the persons to fetch from Wikidata. */
    public Path themesDirectory() {
        return directory.resolve("themes");
    }
}
