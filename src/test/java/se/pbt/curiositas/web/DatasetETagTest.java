package se.pbt.curiositas.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the ETag of the whole dataset, since clients compare it to decide whether to download
 * the data again.
 */
class DatasetETagTest {

    /** Joins the version of the dataset with the release, so either one changing changes the tag. */
    @Test
    void joinsDatasetVersionAndRelease() {
        assertThat(new DatasetETag("0.7.0").of(57)).isEqualTo("57-0.7.0");
        assertThat(new DatasetETag("0.7.0").of(58)).isNotEqualTo(new DatasetETag("0.7.0").of(57));
        assertThat(new DatasetETag("0.8.0").of(57)).isNotEqualTo(new DatasetETag("0.7.0").of(57));
    }
}
