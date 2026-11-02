package se.pbt.curiositas.person;

import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.UUID;

/**
 * Creates persons that look as if they had been loaded from the database, for tests that run
 * without one. The id, version and timestamps are normally set only by JPA.
 */
public final class StoredPersons {

    /** The creation and update time given to every stored test person. */
    public static final Instant STORED_AT = Instant.parse("2026-10-09T12:00:00Z");

    /** Not instantiable; all methods are static. */
    private StoredPersons() {
    }

    /**
     * Gives a person the values the database layer would have assigned.
     *
     * @param person  the person to complete
     * @param id      the id to assign
     * @param version the version to assign
     * @return the same person, now with id, version and timestamps
     */
    public static Person stored(Person person, UUID id, long version) {
        ReflectionTestUtils.setField(person, "id", id);
        ReflectionTestUtils.setField(person, "version", version);
        ReflectionTestUtils.setField(person, "createdAt", STORED_AT);
        ReflectionTestUtils.setField(person, "updatedAt", STORED_AT);
        return person;
    }

    /**
     * Creates a revision as the database layer would have stored it.
     *
     * @param sequenceNumber the sequence number to assign
     * @param person         the stored person the revision describes
     * @param revision       the number of the change for that person
     * @param changeType     what the change did
     * @return the revision, holding a snapshot of the person
     */
    public static PersonRevision storedRevision(long sequenceNumber, Person person, int revision,
                                                ChangeType changeType) {
        PersonRevision stored = new PersonRevision(person.getId(), revision, changeType, ChangeSource.API,
                STORED_AT, STORED_AT, PersonSnapshots.of(person));
        ReflectionTestUtils.setField(stored, "sequenceNumber", sequenceNumber);
        return stored;
    }
}
