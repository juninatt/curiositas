package se.pbt.curiositas.person;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Stores and finds the revisions of persons. Revisions are only ever added.
 */
public interface PersonRevisionRepository extends JpaRepository<PersonRevision, Long> {

    /**
     * Finds every revision of a person, oldest first.
     *
     * @param personId the id of the person, which may have been deleted
     * @return the revisions, or an empty list if the person never existed
     */
    List<PersonRevision> findByPersonIdOrderByRevision(UUID personId);

    /**
     * Finds the changes made after the given one, in the order they were committed.
     *
     * @param after the sequence number of the last change already known, or 0 for all changes
     * @param limit the most changes to return
     * @return the changes, oldest first
     */
    List<PersonRevision> findBySequenceNumberGreaterThanOrderBySequenceNumber(long after, Limit limit);

    /**
     * Finds the latest revision of a person.
     *
     * @param personId the id of the person, which may have been deleted
     * @return the latest revision, or nothing if the person never existed
     */
    Optional<PersonRevision> findFirstByPersonIdOrderByRevisionDesc(UUID personId);

    /**
     * Finds the number of the latest revision of a person.
     *
     * @param personId the id of the person
     * @return the highest revision number, or 0 if the person has none
     */
    @Query("select coalesce(max(r.revision), 0) from PersonRevision r where r.personId = :personId")
    int findLatestRevision(UUID personId);

    /**
     * Waits until no other transaction holds the lock with the given key, then holds it until this
     * transaction commits or rolls back. Only transactions that ask for the same key wait for each
     * other; the lock blocks no rows or tables.
     *
     * @param key identifies the lock
     * @return always 1, since a native query must return something
     */
    @Query(value = "select 1 from pg_advisory_xact_lock(:key)", nativeQuery = true)
    int lockUntilCommit(long key);

    /**
     * Finds the sequence number of the latest change, which is the version of the dataset.
     *
     * @return the highest sequence number, or 0 if nothing has changed yet
     */
    @Query("select coalesce(max(r.sequenceNumber), 0) from PersonRevision r")
    long findLatestSequenceNumber();

    /**
     * Finds every person that has at least one revision, including deleted ones.
     *
     * @return the ids of those persons
     */
    @Query("select distinct r.personId from PersonRevision r")
    Set<UUID> findPersonIds();
}
