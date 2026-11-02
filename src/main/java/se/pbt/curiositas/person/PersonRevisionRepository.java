package se.pbt.curiositas.person;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
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
     * Finds the number of the latest revision of a person.
     *
     * @param personId the id of the person
     * @return the highest revision number, or 0 if the person has none
     */
    @Query("select coalesce(max(r.revision), 0) from PersonRevision r where r.personId = :personId")
    int findLatestRevision(UUID personId);
}
