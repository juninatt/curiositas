package se.pbt.curiositas.data;

import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Where the database and the data files disagree, keyed by file. Since the files are the source of
 * truth, any difference is a fault to fix, never a state to live with.
 *
 * @param byFile what differs, keyed by file; empty when everything matches
 */
public record DataDifferences(SortedMap<String, List<String>> byFile) {

    /** What to do about differences, shown with every report of them. */
    public static final String HOW_TO_FIX = """
            If the files are right, for example after git pull or checkout, update the database from them:
                .\\data.cmd import        (macOS and Linux: ./data.sh import)
            If the database holds changes you want to keep, write them to the files and review them with git diff:
                .\\data.cmd export        (macOS and Linux: ./data.sh export)""";

    /**
     * Creates the differences, sorted by file.
     *
     * @param byFile what differs, keyed by file
     */
    public DataDifferences(Map<String, List<String>> byFile) {
        this(new TreeMap<>(byFile));
    }

    /** Returns whether the database and the files match. */
    public boolean isEmpty() {
        return byFile.isEmpty();
    }

    /**
     * Combines these differences with others, keeping every problem of a file that has both.
     *
     * @param other more differences
     * @return all differences together
     */
    public DataDifferences and(DataDifferences other) {
        Map<String, List<String>> combined = new TreeMap<>(byFile);
        other.byFile.forEach((file, problems) -> combined.merge(file, problems,
                (first, second) -> Stream.concat(first.stream(), second.stream()).toList()));
        return new DataDifferences(combined);
    }

    /** Lists each file that differs, with what differs indented below it. */
    public String describe() {
        return FileReport.describe(byFile);
    }
}
