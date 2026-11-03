package se.pbt.curiositas.wikidata;

/**
 * A value in a Wikidata statement, of the kinds Curiositas reads: another item, a point in time
 * or a text. Values of other kinds are kept as {@link Unsupported}, so reading never fails on data
 * Curiositas does not use.
 */
public sealed interface WikidataValue {

    /**
     * Another Wikidata item, such as a place or a gender.
     *
     * @param id the item's id, for example {@code Q220} for Rome
     */
    record Item(String id) implements WikidataValue {
    }

    /**
     * A point in time, exactly as Wikidata gives it.
     *
     * <p>The digits beyond the precision carry no meaning: a date known to the month may still
     * show a day. Years before Christ are negative without a year 0, so {@code -0100} is 100 BC.
     *
     * @param time      the time, for example {@code -0100-07-12T00:00:00Z}
     * @param precision how much of the time is known: 11 the day, 10 the month, 9 the year,
     *                  8 the decade, 7 the century, 6 the millennium
     * @param calendar  the id of the calendar the date is written in, {@code Q1985727} for the
     *                  Gregorian and {@code Q1985786} for the Julian calendar
     */
    record Time(String time, int precision, String calendar) implements WikidataValue {
    }

    /**
     * A text, such as a name.
     *
     * @param text the text
     */
    record Text(String text) implements WikidataValue {
    }

    /**
     * A value of a kind Curiositas does not read, such as a quantity or coordinates.
     *
     * @param type Wikidata's name for the kind of value
     */
    record Unsupported(String type) implements WikidataValue {
    }
}
