package se.pbt.curiositas.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Makes the ETags of responses that show many persons at once, such as a page of persons or the
 * snapshot, so a client can ask whether anything changed and get 304 Not Modified if not.
 *
 * <p>The tag joins the version of the dataset with the version of the application. The dataset
 * version changes with every change to the data; the application version changes with every
 * release, which may change how the data is shown, for example a display text, without changing
 * the data itself.
 */
@Component
public class DatasetETag {

    private final String release;

    /**
     * Creates the tag maker.
     *
     * @param release the version of this release, filled in from the build
     */
    public DatasetETag(@Value("${curiositas.release}") String release) {
        this.release = release;
    }

    /**
     * Returns the tag for the dataset at the given version, without the quotes Spring adds.
     *
     * <p>The version must be read no later than the data the response shows. A tag older than the
     * data only costs a client one more download; a tag newer than the data would make it keep
     * outdated data, answered with 304 every time it asks.
     *
     * @param version the version of the dataset the response shows
     * @return the tag, for example {@code 57-0.7.0}
     */
    public String of(long version) {
        return version + "-" + release;
    }
}
