package se.pbt.curiositas.person;

import org.springframework.data.domain.Limit;
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
 *
 * <p>Writes take turns, so revisions are numbered in the order they are committed. Without that,
 * two writes at the same moment could commit in the opposite order of their numbers, and a client
 * reading the changes in between would see the later number and miss the earlier one for good.
 */
@Component
public class PersonHistory {

    /** The key of the lock writes take turns with; any number works, as long as nothing else uses it. */
    private static final long WRITE_TURN = 0x4355_5249L;

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
     * Waits for this transaction's turn to write, and keeps it until the transaction commits or
     * rolls back. Reads never wait, since they do not ask for a turn.
     *
     * <p>Must come before the transaction reads anything it is about to change, so the data it
     * checks cannot change before it writes, and before it changes any row. A transaction that
     * held a row while waiting for its turn could wait for a transaction that, holding the turn,
     * waits for that row, and neither would ever finish.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void takeTurnToWrite() {
        revisions.lockUntilCommit(WRITE_TURN);
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
     * Returns the changes made after the given one, in the order they were committed. Since writes
     * take turns, a change can never later appear before one already returned.
     *
     * @param after the sequence number of the last change already known, or 0 for all changes
     * @param limit the most changes to return
     * @return the changes, oldest first
     */
    public List<PersonRevision> changesAfter(long after, int limit) {
        return revisions.findBySequenceNumberGreaterThanOrderBySequenceNumber(after, Limit.of(limit));
    }

    /**
     * Returns the version of the dataset: the sequence number of the latest committed change.
     *
     * @return the version, or 0 if nothing has changed yet
     */
    public long version() {
        return revisions.findLatestSequenceNumber();
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

    /**
     * Stores the next revision of the person. Takes the turn to write as well, in case the change
     * did not, so no revision is ever numbered out of order; taking it again changes nothing.
     */
    private void record(Person person, ChangeType changeType, ChangeSource source, Instant changedAt) {
        takeTurnToWrite();
        int revision = revisions.findLatestRevision(person.getId()) + 1;
        revisions.save(new PersonRevision(person.getId(), revision, changeType, source, changedAt,
                person.getCreatedAt(), PersonSnapshots.of(person)));
    }
}
