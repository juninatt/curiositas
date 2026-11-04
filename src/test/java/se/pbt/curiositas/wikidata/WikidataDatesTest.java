package se.pbt.curiositas.wikidata;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import se.pbt.curiositas.date.HistoricalDate;
import se.pbt.curiositas.wikidata.WikidataStatement.Rank;
import se.pbt.curiositas.wikidata.WikidataValue.Item;
import se.pbt.curiositas.wikidata.WikidataValue.Time;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that Wikidata's dates become dates that are never more precise than the sources, and
 * that disagreeing sources are combined and shown instead of one being chosen silently. The
 * expected texts for decades, centuries and millennia follow how Wikidata itself shows them.
 */
class WikidataDatesTest {

    private static final String GREGORIAN = "Q1985727";
    private static final String JULIAN = "Q1985786";

    /**
     * Keeps only what the precision covers, and turns years before Christ, which Wikidata numbers
     * without a year 0, into the astronomical years Curiositas stores.
     */
    @ParameterizedTest(name = "{0} at precision {1} is {2}")
    @CsvSource(delimiter = '|', textBlock = """
            +1718-11-22T00:00:00Z | 11 | 22 Nov 1718
            +1718-11-22T13:45:00Z | 13 | 22 Nov 1718
            -0100-07-12T00:00:00Z | 10 | Jul 100 BC
            +1680-01-01T00:00:00Z |  9 | 1680
            +0001-00-00T00:00:00Z |  9 | 1
            -0001-00-00T00:00:00Z |  9 | 1 BC
            +1718-11-00T00:00:00Z | 11 | Nov 1718
            +1718-00-00T00:00:00Z | 11 | 1718
            +1718-04-31T00:00:00Z | 11 | Apr 1718
            +1690-00-00T00:00:00Z |  8 | 1695 ± 5
            +1699-00-00T00:00:00Z |  8 | 1695 ± 5
            -0100-00-00T00:00:00Z |  8 | 104 BC ± 5
            +1700-00-00T00:00:00Z |  7 | 1651 ± 50
            +1750-00-00T00:00:00Z |  7 | 1751 ± 50
            +1801-00-00T00:00:00Z |  7 | 1851 ± 50
            -0100-00-00T00:00:00Z |  7 | 50 BC ± 50
            +2000-00-00T00:00:00Z |  6 | 1501 ± 500
            """)
    void keepsOnlyWhatThePrecisionCovers(String time, int precision, String expected) {
        assertThat(WikidataDates.toDate(new Time(time, precision, GREGORIAN)).displayText()).isEqualTo(expected);
    }

    /** Gives no date for times it cannot read or that are less precise than a millennium. */
    @ParameterizedTest
    @CsvSource({"+1718-00-00T00:00:00Z, 5", "+0000-00-00T00:00:00Z, 9", "1718, 9"})
    void givesNoDateForUnreadableTimes(String time, int precision) {
        assertThat(WikidataDates.toDate(new Time(time, precision, GREGORIAN))).isNull();
    }

    /**
     * Uses Wikidata's preferred birth date for Julius Caesar, known only to the month, and lists
     * the other dates the sources give, since one of them is two years earlier.
     */
    @Test
    void readsCaesarsDisputedBirth() {
        WikidataDate birth = WikidataDates.read(WikidataFixtures.entity("Q1048").statements("P569"));

        assertThat(birth.date().displayText()).isEqualTo("Jul 100 BC");
        assertThat(birth.sources()).extracting(HistoricalDate::displayText)
                .containsExactly("Jul 100 BC", "13 Jul 100 BC", "102 BC", "100 BC");
        assertThat(birth.sourcesDisagree()).isTrue();
    }

    /** Uses the exact date of Caesar's death; a source giving only the year agrees with it. */
    @Test
    void readsCaesarsDeath() {
        WikidataDate death = WikidataDates.read(WikidataFixtures.entity("Q1048").statements("P570"));

        assertThat(death.date().displayText()).isEqualTo("15 Mar 44 BC");
        assertThat(death.sources()).extracting(HistoricalDate::displayText).containsExactly("15 Mar 44 BC", "44 BC");
        assertThat(death.sourcesDisagree()).isFalse();
    }

    /**
     * Reads Anne Bonny's birth as "circa 1697", leaves out the deprecated exact date, and accepts
     * 1700 as agreeing with it.
     */
    @Test
    void readsBonnysApproximateBirth() {
        WikidataDate birth = WikidataDates.read(WikidataFixtures.entity("Q231549").statements("P569"));

        assertThat(birth.date().displayText()).isEqualTo("1697 ± 5");
        assertThat(birth.sources()).extracting(HistoricalDate::displayText).containsExactly("1697 ± 5", "1700");
        assertThat(birth.sourcesDisagree()).isFalse();
    }

    /** Gives no date when Wikidata says the date is unknown. */
    @Test
    void readsUnknownDeathAsNoDate() {
        WikidataDate death = WikidataDates.read(WikidataFixtures.entity("Q231549").statements("P570"));

        assertThat(death.date()).isNull();
        assertThat(death.sources()).isEmpty();
    }

    /** Combines normal statements into what they share when none is preferred. */
    @Test
    void combinesNormalStatementsWithoutPreferredOne() {
        WikidataDate days = WikidataDates.read(List.of(
                statement(Rank.NORMAL, "+1718-07-12T00:00:00Z", 11),
                statement(Rank.NORMAL, "+1718-07-13T00:00:00Z", 11)));
        WikidataDate years = WikidataDates.read(List.of(
                statement(Rank.NORMAL, "+1680-00-00T00:00:00Z", 9),
                statement(Rank.NORMAL, "+1690-00-00T00:00:00Z", 9)));

        assertThat(days.date().displayText()).isEqualTo("Jul 1718");
        assertThat(days.sourcesDisagree()).isTrue();
        assertThat(years.date().displayText()).isEqualTo("1685 ± 5");
        assertThat(years.sourcesDisagree()).isTrue();
    }

    /** Combines several preferred statements too, and lets them win over the normal ones. */
    @Test
    void combinesPreferredStatements() {
        WikidataDate date = WikidataDates.read(List.of(
                statement(Rank.NORMAL, "+1600-00-00T00:00:00Z", 9),
                statement(Rank.PREFERRED, "+1718-07-12T00:00:00Z", 11),
                statement(Rank.PREFERRED, "+1718-08-01T00:00:00Z", 11)));

        assertThat(date.date().displayText()).isEqualTo("1718");
        assertThat(date.sources()).extracting(HistoricalDate::displayText)
                .containsExactly("12 Jul 1718", "1 Aug 1718", "1600");
    }

    /** Ignores deprecated statements, even when they are the only ones. */
    @Test
    void ignoresDeprecatedStatements() {
        WikidataDate date = WikidataDates.read(List.of(statement(Rank.DEPRECATED, "+1718-00-00T00:00:00Z", 9)));

        assertThat(date.date()).isNull();
        assertThat(date.sources()).isEmpty();
    }

    /** Ignores statements whose value is not a time, such as an unknown value. */
    @Test
    void ignoresStatementsWithoutTime() {
        WikidataDate date = WikidataDates.read(List.of(
                new WikidataStatement("P569", Rank.PREFERRED, null, Map.of()),
                new WikidataStatement("P569", Rank.NORMAL, new Item("Q220"), Map.of()),
                statement(Rank.NORMAL, "+1718-00-00T00:00:00Z", 9)));

        assertThat(date.date().displayText()).isEqualTo("1718");
    }

    /** Widens a date to its earliest and latest date when the source gives them. */
    @Test
    void usesEarliestAndLatestDate() {
        WikidataStatement statement = new WikidataStatement("P569", Rank.NORMAL,
                new Time("+1690-00-00T00:00:00Z", 9, GREGORIAN),
                Map.of(WikidataDates.EARLIEST_DATE, List.of(new Time("+1685-00-00T00:00:00Z", 9, GREGORIAN)),
                        WikidataDates.LATEST_DATE, List.of(new Time("+1695-00-00T00:00:00Z", 9, GREGORIAN))));

        assertThat(WikidataDates.toDate(statement).displayText()).isEqualTo("1690 ± 5");
    }

    /** Makes an approximate date a year with an uncertainty, but never narrows a wider one. */
    @Test
    void readsCircaAsUncertainty() {
        assertThat(WikidataDates.toDate(circa("+1718-11-22T00:00:00Z", 11)).displayText()).isEqualTo("1718 ± 5");
        assertThat(WikidataDates.toDate(circa("+1750-00-00T00:00:00Z", 7)).displayText()).isEqualTo("1751 ± 50");
    }

    /** Keeps a date in the calendar the source gives, without converting it. */
    @Test
    void keepsCalendarOfSource() {
        assertThat(WikidataDates.toDate(new Time("-0044-03-15T00:00:00Z", 11, JULIAN)).displayText())
                .isEqualTo("15 Mar 44 BC");
    }

    /**
     * Tells dates that cannot both be true from dates where one is only more precise than the
     * other.
     */
    @ParameterizedTest(name = "{0} and {1} contradict: {2}")
    @CsvSource(delimiter = '|', textBlock = """
            -99, 7,   , 0 | -99,  ,   , 0 | false
            -99, 7, 12, 0 | -99, 7,   , 0 | false
            -99, 7, 12, 0 | -99, 7, 13, 0 | true
            -99, 7,   , 0 | -99, 8,   , 0 | true
            -99,  ,   , 0 | -101, ,   , 0 | true
            1697, ,   , 5 | 1700, ,   , 0 | false
            1697, ,   , 1 | 1700, ,   , 0 | true
            """)
    void tellsWhenDatesContradict(String first, String second, boolean expected) {
        assertThat(WikidataDates.contradict(date(first), date(second))).isEqualTo(expected);
        assertThat(WikidataDates.contradict(date(second), date(first))).isEqualTo(expected);
    }

    /** Creates a statement with a time in the Gregorian calendar. */
    private static WikidataStatement statement(Rank rank, String time, int precision) {
        return new WikidataStatement("P569", rank, new Time(time, precision, GREGORIAN), Map.of());
    }

    /** Creates a statement marked as "circa". */
    private static WikidataStatement circa(String time, int precision) {
        return new WikidataStatement("P569", Rank.NORMAL, new Time(time, precision, GREGORIAN),
                Map.of(WikidataDates.SOURCING_CIRCUMSTANCES, List.of(new Item(WikidataDates.CIRCA))));
    }

    /** Reads a date written as "year, month, day, uncertainty", with empty parts unknown. */
    private static HistoricalDate date(String text) {
        String[] parts = text.split(",", -1);
        return new HistoricalDate(Integer.parseInt(parts[0].strip()), number(parts[1]), number(parts[2]),
                Integer.parseInt(parts[3].strip()));
    }

    /** Reads a number, or null when the text is empty. */
    private static Integer number(String text) {
        return text.isBlank() ? null : Integer.valueOf(text.strip());
    }
}
