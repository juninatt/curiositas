package se.pbt.curiositas.wikidata;

import java.util.List;
import java.util.Map;

/**
 * An item on Wikidata, such as a person or a place, with its English label and its statements.
 *
 * @param id         the item's id, for example {@code Q1048} for Julius Caesar
 * @param label      the English label, or {@code null} if the item has none
 * @param statements every statement, by property, in the order Wikidata gives them
 */
public record WikidataEntity(String id, String label, Map<String, List<WikidataStatement>> statements) {

    /** Copies the statements, so the entity cannot change after it was read. */
    public WikidataEntity {
        statements = Map.copyOf(statements);
    }

    /**
     * Returns the statements for one property.
     *
     * @param property the property, for example {@code P569} for the date of birth
     * @return the statements, empty if there are none
     */
    public List<WikidataStatement> statements(String property) {
        return statements.getOrDefault(property, List.of());
    }
}
