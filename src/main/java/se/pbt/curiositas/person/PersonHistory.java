package se.pbt.curiositas.person;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Records every change to a person as a revision, in the same transaction as the change, so the
 * history can never disagree with the data: either both are stored or neither is.
 */
@Component
public class PersonHistory {

    private final PersonRevisionRepository revisions;

    /**
     * Creates the history.
     *
     * @param revisions stores the revisions
     */
    public PersonHistory(PersonRevisionRepository revisions) {
        this.revisions = revisions;
    }

    /**
     * Records that a person was created, as it is now stored.
     *
     * @param person the stored person
     * @param source where the change came from
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void created(Person person, ChangeSource source) {
        record(person, ChangeType.CREATED, source, person.getUpdatedAt());
    }

    /**
     * Records that a person was changed, as it is now stored.
     *
     * @param person the stored person, after the change
     * @param source where the change came from
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void updated(Person person, ChangeSource source) {
        record(person, ChangeType.UPDATED, source, person.getUpdatedAt());
    }

    /**
     * Records that a person is being deleted, keeping the person as it was before.
     *
     * @param person the person about to be deleted
     * @param source where the change came from
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void deleted(Person person, ChangeSource source) {
        record(person, ChangeType.DELETED, source, Instant.now());
    }

    /**
     * Returns every revision of a person, oldest first.
     *
     * @param personId the id of the person, which may have been deleted
     * @return the revisions, or an empty list if the person never existed
     */
    public List<PersonRevision> of(UUID personId) {
        return revisions.findByPersonIdOrderByRevision(personId);
    }

    /**
     * Returns when a person was deleted, if it was.
     *
     * @param personId the id of the person
     * @return the time of the deletion, or nothing if the person exists or never existed
     */
    public Optional<Instant> deletedAt(UUID personId) {
        return revisions.findFirstByPersonIdOrderByRevisionDesc(personId)
                .filter(latest -> latest.getChangeType() == ChangeType.DELETED)
                .map(PersonRevision::getChangedAt);
    }

    /**
     * Returns the persons that have a history. A stored person without one was stored before the
     * history existed, and gets its first revision from the next import.
     *
     * @return the ids of every person with at least one revision, including deleted persons
     */
    public Set<UUID> personsWithHistory() {
        return revisions.findPersonIds();
    }

    /** Stores the next revision of the person. */
    private void record(Person person, ChangeType changeType, ChangeSource source, Instant changedAt) {
        int revision = revisions.findLatestRevision(person.getId()) + 1;
        revisions.save(new PersonRevision(person.getId(), revision, changeType, source, changedAt,
                PersonSnapshots.of(person)));
    }
}
