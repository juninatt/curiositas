package se.pbt.curiositas.data;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;
import se.pbt.curiositas.person.PersonHistory;
import se.pbt.curiositas.person.PersonService;

import java.io.PrintStream;

/**
 * Runs one data command, such as {@code export}, {@code import} or {@code check}, and then
 * stops the application. Started through {@code data.cmd} or {@code data.sh}, which set
 * {@code curiositas.data.command}; the application then runs without a web server, so commands
 * work even when the server could not start.
 */
@Component
@ConditionalOnProperty("curiositas.data.command")
public class DataCommandRunner implements ApplicationRunner {

    private final DataProperties properties;
    private final PersonService persons;
    private final PersonHistory history;
    private final PersonExporter exporter;
    private final PersonImporter importer;
    private final ConfigurableApplicationContext context;
    private final String command;
    private final PrintStream out;

    /**
     * Creates the runner.
     *
     * @param properties where the data files live
     * @param persons    reads the persons to export
     * @param history    tells which persons have a history
     * @param exporter   writes the files
     * @param importer   makes the database match the files
     * @param context    the application, stopped when the command is done
     * @param command    the command to run, from {@code curiositas.data.command}
     */
    public DataCommandRunner(DataProperties properties, PersonService persons, PersonHistory history,
                             PersonExporter exporter, PersonImporter importer, ConfigurableApplicationContext context,
                             @Value("${curiositas.data.command}") String command) {
        this.properties = properties;
        this.persons = persons;
        this.history = history;
        this.exporter = exporter;
        this.importer = importer;
        this.context = context;
        this.command = command.strip();
        this.out = System.out;
    }

    /**
     * Runs the command, prints its outcome in plain words and exits with 0 on success and 1 on
     * failure, so scripts can tell the difference.
     */
    @Override
    public void run(ApplicationArguments arguments) {
        int exitCode;
        try {
            exitCode = switch (command) {
                case "export" -> export();
                case "import" -> importFiles();
                case "check" -> check();
                default -> {
                    out.println("Unknown data command '" + command + "'. Available commands: export, import, check");
                    yield 1;
                }
            };
        } catch (InvalidDataFilesException invalid) {
            out.println("Nothing was imported, since these files have problems:");
            out.println(invalid.getMessage());
            out.println("Fix the files and run the import again.");
            exitCode = 1;
        } catch (RuntimeException failure) {
            out.println("The data command '" + command + "' failed: " + failure.getMessage());
            exitCode = 1;
        }
        int code = exitCode;
        System.exit(SpringApplication.exit(context, () -> code));
    }

    /** Writes every person in the database to the data folder. */
    private int export() {
        PersonExporter.Result result = exporter.export(persons.listAll(), properties.personsDirectory());
        out.printf("Exported persons to %s: %d written, %d unchanged, %d removed.%n",
                properties.personsDirectory(), result.written(), result.unchanged(), result.removed());
        return 0;
    }

    /** Reports whether the database matches the data folder, without changing either. */
    private int check() {
        DataDifferences differences = PersonDataChecker.check(persons.listAll(), history.personsWithHistory(),
                properties.personsDirectory());
        if (differences.isEmpty()) {
            out.println("The database matches the data files in " + properties.personsDirectory() + ".");
            return 0;
        }
        out.println("The database does not match the data files:");
        out.println(differences.describe());
        out.println();
        out.println(DataDifferences.HOW_TO_FIX);
        return 1;
    }

    /** Makes the database match the data folder. */
    private int importFiles() {
        PersonImporter.Result result = importer.importFrom(properties.personsDirectory());
        out.printf("Imported persons from %s: %d created, %d updated, %d unchanged, %d deleted.%n",
                properties.personsDirectory(), result.created(), result.updated(), result.unchanged(), result.deleted());
        if (result.historyStarted() > 0) {
            out.printf("Started the history of %d persons that were stored before the history existed.%n",
                    result.historyStarted());
        }
        return 0;
    }
}
