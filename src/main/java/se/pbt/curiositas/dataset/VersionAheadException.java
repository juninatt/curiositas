package se.pbt.curiositas.dataset;

/**
 * Thrown when a client asks for the changes after a version the dataset has not reached, which
 * happens when its copy comes from another database, for example one rebuilt from the data files.
 * Such a copy can never be brought in sync by changes, so the client must load the snapshot again.
 */
public class VersionAheadException extends RuntimeException {

    /**
     * Creates the exception with a message naming both versions and what to do.
     *
     * @param after  the version the client asked about
     * @param latest the latest version of the dataset
     */
    public VersionAheadException(long after, long latest) {
        super("Version " + after + " is beyond the latest version " + latest
                + "; load /api/v1/snapshot again, since this copy does not come from this dataset");
    }
}
