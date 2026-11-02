package se.pbt.curiositas.person;

import java.time.Instant;
import java.util.UUID;

/**
 * Thrown when a requested person has been deleted, so the API can answer 410 Gone instead of
 * 404 Not Found. A client keeping a copy of the data then knows to remove the person, rather
 * than wondering whether the id was ever valid.
 */
public class PersonDeletedException extends RuntimeException {

    /**
     * Creates the exception with a message naming the person, when it was deleted, and where its
     * history can still be read.
     *
     * @param id        the id that was asked for
     * @param deletedAt when the person was deleted
     */
    public PersonDeletedException(UUID id, Instant deletedAt) {
        super("The person with id " + id + " was deleted at " + deletedAt
                + "; its history is at /api/v1/persons/" + id + "/revisions");
    }
}
