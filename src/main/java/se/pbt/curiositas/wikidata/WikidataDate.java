package se.pbt.curiositas.wikidata;

import se.pbt.curiositas.date.HistoricalDate;

import java.util.List;

/**
 * A date read from Wikidata, together with every date the sources give for it, so a review can
 * see when the sources disagree instead of trusting a silent choice.
 *
 * @param date            the date, or {@code null} when Wikidata gives none that can be read
 * @param sources         every distinct date the preferred and normal statements give, preferred
 *                        first; deprecated statements are left out
 * @param sourcesDisagree whether two of those dates cannot both be true, such as 100 BC and
 *                        102 BC, rather than one only being more precise than the other
 */
public record WikidataDate(HistoricalDate date, List<HistoricalDate> sources, boolean sourcesDisagree) {

    /** Copies the sources, so the result cannot change after it was read. */
    public WikidataDate {
        sources = List.copyOf(sources);
    }
}
