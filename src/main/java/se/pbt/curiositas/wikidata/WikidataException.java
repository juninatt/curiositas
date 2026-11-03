package se.pbt.curiositas.wikidata;

/**
 * Thrown when items cannot be read from Wikidata: it cannot be reached, answers with an error, or
 * does not know an id. The message says which, so the person running the fetch knows what to fix.
 */
public class WikidataException extends RuntimeException {

    /**
     * Creates the exception.
     *
     * @param message what went wrong, in plain words
     */
    public WikidataException(String message) {
        super(message);
    }

    /**
     * Creates the exception with its cause.
     *
     * @param message what went wrong, in plain words
     * @param cause   the error that caused it
     */
    public WikidataException(String message, Throwable cause) {
        super(message, cause);
    }
}
