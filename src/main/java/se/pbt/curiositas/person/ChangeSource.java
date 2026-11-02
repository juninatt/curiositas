package se.pbt.curiositas.person;

/**
 * Where a change came from, so every fact in the history can be traced to how it got there.
 */
public enum ChangeSource {
    /** Made by an admin through the REST API. */
    API,
    /** Imported from the data files in the repository. */
    IMPORT,
    /** Fetched from Wikidata. */
    WIKIDATA
}
