package se.pbt.curiositas.wikidata;

import java.util.List;
import java.util.Map;

/**
 * One statement about an item, such as one of the birth dates Wikidata knows for a person. An item
 * may have several statements for the same property when sources disagree; the rank tells which
 * one Wikidata considers best.
 *
 * @param property   the property, for example {@code P569} for the date of birth
 * @param rank       how Wikidata ranks this statement against the others for the same property
 * @param value      the value, or {@code null} when Wikidata says it is unknown or that there is none
 * @param qualifiers values that refine the statement, by property, for example {@code P1480} for
 *                   "circa"; unknown values are left out
 */
public record WikidataStatement(String property, Rank rank, WikidataValue value,
                                Map<String, List<WikidataValue>> qualifiers) {

    /** Copies the qualifiers, so the statement cannot change after it was read. */
    public WikidataStatement {
        qualifiers = Map.copyOf(qualifiers);
    }

    /**
     * Returns the values of one qualifier.
     *
     * @param qualifier the qualifier's property
     * @return the values, empty if the statement has none
     */
    public List<WikidataValue> qualifier(String qualifier) {
        return qualifiers.getOrDefault(qualifier, List.of());
    }

    /** How Wikidata ranks statements for the same property against each other. */
    public enum Rank {
        /** The best statement, chosen over the normal ones. */
        PREFERRED,
        /** A statement without special standing. */
        NORMAL,
        /** A statement known to be wrong, kept only to record that a source claimed it. */
        DEPRECATED
    }
}
