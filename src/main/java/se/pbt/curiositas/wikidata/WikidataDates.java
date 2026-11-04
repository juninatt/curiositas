package se.pbt.curiositas.wikidata;

import se.pbt.curiositas.date.HistoricalDate;
import se.pbt.curiositas.wikidata.WikidataStatement.Rank;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns Wikidata's dates into {@link HistoricalDate}s that are never more precise than the sources.
 *
 * <p>A date is read with its precision: a date known to the month keeps no day, even when Wikidata
 * shows one, and a decade or century becomes a year with an uncertainty that covers it. Years
 * before Christ, which Wikidata numbers without a year 0, become astronomical years, so 100 BC is
 * year -99. Dates are kept in the calendar the source gives, as everywhere in Curiositas.
 *
 * <p>When a property has several statements, the preferred ones are used if there are any, and
 * otherwise the normal ones; deprecated statements are known to be wrong and are ignored. When the
 * statements used differ, they are combined into what they have in common, so 12 or 13 July becomes
 * July, and 1680 or 1690 becomes 1685 ± 5. Nothing is chosen silently: the result lists every date
 * the sources give and says whether they disagree.
 */
public final class WikidataDates {

    /** Qualifier for the circumstances of a source, such as "circa". */
    static final String SOURCING_CIRCUMSTANCES = "P1480";

    /** Qualifier for the earliest date a statement allows. */
    static final String EARLIEST_DATE = "P1319";

    /** Qualifier for the latest date a statement allows. */
    static final String LATEST_DATE = "P1326";

    /** The value of {@link #SOURCING_CIRCUMSTANCES} that marks a date as approximate. */
    static final String CIRCA = "Q5727902";

    /** The uncertainty given to an approximate date, when the source does not say how approximate. */
    static final int CIRCA_YEARS = 5;

    private static final int DAY = 11;
    private static final int MONTH = 10;
    private static final int YEAR = 9;
    private static final int DECADE = 8;
    private static final int CENTURY = 7;
    private static final int MILLENNIUM = 6;

    private static final Pattern TIME = Pattern.compile("^([+-])(\\d+)-(\\d{2})-(\\d{2})T");

    /** Not instantiable; all methods are static. */
    private WikidataDates() {
    }

    /**
     * Reads a date from all statements for one property, such as every date of birth of a person.
     *
     * @param statements the statements, in Wikidata's order
     * @return the date with every date the sources give; the date is {@code null} when no statement
     *         gives one that can be read
     */
    public static WikidataDate read(List<WikidataStatement> statements) {
        List<HistoricalDate> preferred = new ArrayList<>();
        List<HistoricalDate> normal = new ArrayList<>();
        for (WikidataStatement statement : statements) {
            HistoricalDate date = toDate(statement);
            if (date == null || statement.rank() == Rank.DEPRECATED) {
                continue;
            }
            (statement.rank() == Rank.PREFERRED ? preferred : normal).add(date);
        }
        List<HistoricalDate> sources = new ArrayList<>(new LinkedHashSet<>(concat(preferred, normal)));
        if (sources.isEmpty()) {
            return new WikidataDate(null, List.of(), false);
        }
        HistoricalDate date = common(preferred.isEmpty() ? normal : preferred);
        return new WikidataDate(date, sources, anyContradict(sources));
    }

    /**
     * Turns one statement into a date, with its precision, its earliest and latest date, and
     * "circa" taken into account.
     *
     * @param statement a statement whose value is a point in time
     * @return the date, or {@code null} if the value is unknown, not a time, or less precise than
     *         a millennium
     */
    static HistoricalDate toDate(WikidataStatement statement) {
        if (!(statement.value() instanceof WikidataValue.Time time)) {
            return null;
        }
        HistoricalDate date = toDate(time);
        if (date == null) {
            return null;
        }
        List<HistoricalDate> bounds = new ArrayList<>(List.of(date));
        for (String qualifier : List.of(EARLIEST_DATE, LATEST_DATE)) {
            for (WikidataValue bound : statement.qualifier(qualifier)) {
                if (bound instanceof WikidataValue.Time boundTime && toDate(boundTime) != null) {
                    bounds.add(toDate(boundTime));
                }
            }
        }
        date = common(bounds);
        if (statement.qualifier(SOURCING_CIRCUMSTANCES).contains(new WikidataValue.Item(CIRCA))) {
            date = new HistoricalDate(date.year(), null, null, Math.max(date.uncertaintyYears(), CIRCA_YEARS));
        }
        return date;
    }

    /**
     * Turns a point in time into a date with only the parts its precision covers.
     *
     * @return the date, or {@code null} if it cannot be read or is less precise than a millennium
     */
    static HistoricalDate toDate(WikidataValue.Time time) {
        Matcher parts = TIME.matcher(time.time());
        if (!parts.find()) {
            return null;
        }
        int magnitude = Integer.parseInt(parts.group(2));
        if (magnitude == 0) {
            return null;
        }
        boolean beforeChrist = parts.group(1).equals("-");
        int month = Integer.parseInt(parts.group(3));
        int day = Integer.parseInt(parts.group(4));
        int year = beforeChrist ? 1 - magnitude : magnitude;
        return switch (time.precision()) {
            case DAY, MONTH -> {
                if (month == 0) {
                    yield new HistoricalDate(year, null, null, 0);
                }
                if (time.precision() == MONTH || day == 0) {
                    yield new HistoricalDate(year, month, null, 0);
                }
                yield dayOrMonth(year, month, day);
            }
            case YEAR -> new HistoricalDate(year, null, null, 0);
            case DECADE -> span(beforeChrist, magnitude / 10 * 10, magnitude / 10 * 10 + 9);
            case CENTURY -> span(beforeChrist, (magnitude + 99) / 100 * 100 - 99, (magnitude + 99) / 100 * 100);
            case MILLENNIUM -> span(beforeChrist, (magnitude + 999) / 1000 * 1000 - 999, (magnitude + 999) / 1000 * 1000);
            default -> time.precision() > DAY ? dayOrMonth(year, month, day) : null;
        };
    }

    /**
     * Combines dates into what they have in common: the same date if they all agree, the month or
     * the year they share, and otherwise a year with an uncertainty that covers all of them.
     *
     * @param dates at least one date
     * @return the date they have in common
     */
    static HistoricalDate common(List<HistoricalDate> dates) {
        HistoricalDate first = dates.getFirst();
        if (dates.stream().allMatch(first::equals)) {
            return first;
        }
        boolean exactYears = dates.stream().allMatch(date -> date.uncertaintyYears() == 0);
        if (exactYears && dates.stream().allMatch(date -> date.year() == first.year())) {
            boolean sameMonth = first.month() != null
                    && dates.stream().allMatch(date -> first.month().equals(date.month()));
            return new HistoricalDate(first.year(), sameMonth ? first.month() : null, null, 0);
        }
        int earliest = dates.stream().mapToInt(date -> date.year() - date.uncertaintyYears()).min().orElseThrow();
        int latest = dates.stream().mapToInt(date -> date.year() + date.uncertaintyYears()).max().orElseThrow();
        return between(earliest, latest);
    }

    /**
     * Tells whether two dates cannot both be true: their years do not overlap, or they fall in the
     * same year but name different months, or the same month but different days.
     */
    static boolean contradict(HistoricalDate a, HistoricalDate b) {
        if (a.year() + a.uncertaintyYears() < b.year() - b.uncertaintyYears()
                || b.year() + b.uncertaintyYears() < a.year() - a.uncertaintyYears()) {
            return true;
        }
        if (a.uncertaintyYears() > 0 || b.uncertaintyYears() > 0 || a.year() != b.year()) {
            return false;
        }
        if (a.month() == null || b.month() == null) {
            return false;
        }
        if (!a.month().equals(b.month())) {
            return true;
        }
        return a.day() != null && b.day() != null && !a.day().equals(b.day());
    }

    /** Tells whether any two of the dates contradict each other. */
    private static boolean anyContradict(List<HistoricalDate> dates) {
        for (int i = 0; i < dates.size(); i++) {
            for (int j = i + 1; j < dates.size(); j++) {
                if (contradict(dates.get(i), dates.get(j))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Returns the day, or only the month if the day cannot exist, such as 31 April. */
    private static HistoricalDate dayOrMonth(int year, int month, int day) {
        try {
            return new HistoricalDate(year, month, day, 0);
        } catch (IllegalArgumentException e) {
            return new HistoricalDate(year, month, null, 0);
        }
    }

    /**
     * Returns the span between two years as Wikidata counts them, before or after Christ, as a
     * year in the middle with an uncertainty that covers the whole span.
     */
    private static HistoricalDate span(boolean beforeChrist, int first, int last) {
        int from = Math.max(first, 1);
        return beforeChrist ? between(1 - last, 1 - from) : between(from, last);
    }

    /** Returns the year in the middle of two astronomical years, with an uncertainty that reaches both. */
    private static HistoricalDate between(int earliest, int latest) {
        int middle = Math.floorDiv(earliest + latest + 1, 2);
        return new HistoricalDate(middle, null, null, Math.max(middle - earliest, latest - middle));
    }

    /** Joins two lists, the first one first. */
    private static List<HistoricalDate> concat(List<HistoricalDate> first, List<HistoricalDate> second) {
        List<HistoricalDate> all = new ArrayList<>(first);
        all.addAll(second);
        return all;
    }
}
