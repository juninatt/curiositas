package se.pbt.curiositas.person;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * One change to a person, with the whole person as it was after the change, or before it for a
 * deletion. Revisions are never changed or removed, so together they are the full history of the
 * data, owned by the application instead of by git.
 */
@Entity
@Immutable
public class PersonRevision {

    /** The position of this change among all changes in the dataset. */
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "revision_sequence")
    @SequenceGenerator(name = "revision_sequence", sequenceName = "revision_sequence", allocationSize = 1)
    private long sequenceNumber;

    private UUID personId;

    /** The position of this change among the changes to the same person, starting at 1. */
    private int revision;

    @Enumerated(EnumType.STRING)
    private ChangeType changeType;

    @Enumerated(EnumType.STRING)
    private ChangeSource source;

    private Instant changedAt;

    /** The person as JSON, in the same form as the data files. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String snapshot;

    /** For JPA only. */
    protected PersonRevision() {
    }

    /**
     * Creates a revision that is not yet stored.
     *
     * @param personId   the person that changed
     * @param revision   the number of this change for that person, starting at 1
     * @param changeType what the change did
     * @param source     where the change came from
     * @param changedAt  when the change was made
     * @param snapshot   the person as JSON, in the same form as the data files
     */
    PersonRevision(UUID personId, int revision, ChangeType changeType, ChangeSource source, Instant changedAt,
                   String snapshot) {
        this.personId = personId;
        this.revision = revision;
        this.changeType = changeType;
        this.source = source;
        this.changedAt = changedAt;
        this.snapshot = snapshot;
    }

    /** Returns the position of this change among all changes in the dataset. */
    public long getSequenceNumber() {
        return sequenceNumber;
    }

    /** Returns the id of the person that changed. */
    public UUID getPersonId() {
        return personId;
    }

    /** Returns the number of this change for the person, starting at 1. */
    public int getRevision() {
        return revision;
    }

    /** Returns what the change did. */
    public ChangeType getChangeType() {
        return changeType;
    }

    /** Returns where the change came from. */
    public ChangeSource getSource() {
        return source;
    }

    /** Returns when the change was made. */
    public Instant getChangedAt() {
        return changedAt;
    }

    /** Returns the person as JSON, after the change or, for a deletion, before it. */
    public String getSnapshot() {
        return snapshot;
    }
}
