package se.pbt.curiositas;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that migration V3 gives revisions stored before it the creation time of their person,
 * since it runs once on databases that already hold data, where a mistake cannot be undone.
 */
class RevisionCreationTimeMigrationTest {

    private static final String STORED = "2026-10-01T10:00:00Z";
    private static final String CHANGED = "2026-10-15T10:00:00Z";
    private static final String DELETED_CREATED = "2026-10-05T10:00:00Z";

    private static final PostgreSQLContainer DATABASE =
            new PostgreSQLContainer(DockerImageName.parse(TestcontainersConfiguration.POSTGRES_IMAGE));

    /** Starts the database, migrates it to V2 and stores data as it looked before V3. */
    @BeforeAll
    static void storeDataFromBeforeTheMigration() throws SQLException {
        DATABASE.start();
        flyway("2").migrate();
        try (Connection connection = connect(); Statement sql = connection.createStatement()) {
            sql.execute("""
                    insert into person (id, version, created_at, updated_at, name, gender)
                    values ('00000000-0000-0000-0000-000000000001', 1, '%s', '%s', 'Edward Teach', 'MALE')
                    """.formatted(STORED, CHANGED));
            sql.execute("""
                    insert into person_revision
                        (sequence_number, person_id, revision, change_type, source, changed_at, snapshot)
                    values
                        (1, '00000000-0000-0000-0000-000000000001', 1, 'CREATED', 'IMPORT', '%s', '{}'),
                        (2, '00000000-0000-0000-0000-000000000002', 1, 'CREATED', 'API', '%s', '{}'),
                        (3, '00000000-0000-0000-0000-000000000002', 2, 'DELETED', 'API', '%s', '{}')
                    """.formatted(CHANGED, DELETED_CREATED, CHANGED));
        }
        flyway("3").migrate();
    }

    /** Stops the database. */
    @AfterAll
    static void stopDatabase() {
        DATABASE.stop();
    }

    /** Gives the revisions of a stored person the person's own creation time. */
    @Test
    void takesCreationTimeFromStoredPerson() throws SQLException {
        assertThat(createdAtOf(1)).isEqualTo(OffsetDateTime.parse(STORED));
    }

    /** Gives every revision of a deleted person the time of its first revision. */
    @Test
    void takesCreationTimeOfDeletedPersonFromItsFirstRevision() throws SQLException {
        assertThat(createdAtOf(2)).isEqualTo(OffsetDateTime.parse(DELETED_CREATED));
        assertThat(createdAtOf(3)).isEqualTo(OffsetDateTime.parse(DELETED_CREATED));
    }

    /** Requires the creation time from now on, so no revision can be stored without one. */
    @Test
    void requiresCreationTimeAfterwards() throws SQLException {
        try (Connection connection = connect();
             ResultSet column = connection.getMetaData().getColumns(null, "public", "person_revision", "person_created_at")) {
            assertThat(column.next()).isTrue();
            assertThat(column.getString("IS_NULLABLE")).isEqualTo("NO");
        }
    }

    /** Reads the creation time stored with the revision that has the given sequence number. */
    private static OffsetDateTime createdAtOf(long sequenceNumber) throws SQLException {
        try (Connection connection = connect();
             Statement sql = connection.createStatement();
             ResultSet row = sql.executeQuery(
                     "select person_created_at from person_revision where sequence_number = " + sequenceNumber)) {
            assertThat(row.next()).isTrue();
            return row.getObject(1, OffsetDateTime.class).withOffsetSameInstant(ZoneOffset.UTC);
        }
    }

    /** Configures Flyway with the application's migrations, up to the given version. */
    private static Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword())
                .target(target)
                .load();
    }

    /** Opens a connection to the test database. */
    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
    }
}
