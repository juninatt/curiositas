package se.pbt.curiositas.person;

/**
 * What a change did to a record, so a client keeping in sync knows whether to add, update or
 * remove it.
 */
public enum ChangeType {
    CREATED,
    UPDATED,
    DELETED
}
