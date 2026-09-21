package com.coobi.logistics.logisticsapi.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.coobi.logistics.logisticsapi.alert.AlertRecord;
import com.coobi.logistics.logisticsapi.vehicle.VehicleLatestState;
import jakarta.persistence.Column;
import jakarta.persistence.Table;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The API maps tables it does not own, so the mapping and the schema can drift apart without
 * anything failing: the API would still start, and a request would fail at query time on a
 * column that no longer exists.
 *
 * <p>This test closes that gap. It reads the migrations of the stream processor - the service
 * that owns the schema (MVP-4) - and asserts that every column the entity declares is a
 * column the migration creates, and the other way round: a column added, renamed or removed
 * in the owning service fails here, in the service that would break.
 */
class SchemaContractTest {

    private static final String LATEST_STATE_MIGRATION = "V1__create_vehicle_tables.sql";
    private static final String ALERTS_MIGRATION = "V2__create_alerts_table.sql";

    @Test
    void mapsTheLatestVehicleStateColumnsOfTheVehicleMigration() throws IOException {
        assertMapsTheTableOf(LATEST_STATE_MIGRATION, "vehicle_latest_state", VehicleLatestState.class);
    }

    @Test
    void mapsTheAlertColumnsOfTheAlertMigration() throws IOException {
        assertMapsTheTableOf(ALERTS_MIGRATION, "alerts", AlertRecord.class);
    }

    private static void assertMapsTheTableOf(String migration, String table, Class<?> entity)
            throws IOException {
        Set<String> created = columnsOf(migrationOf(migration), table);
        Set<String> mapped = mappedColumnsOf(entity);

        assertThat(tableOf(entity))
                .as("the table %s maps", entity.getSimpleName())
                .isEqualTo(table);
        assertThat(mapped)
                .as("the columns of %s and of %s", entity.getSimpleName(), migration)
                .containsExactlyInAnyOrderElementsOf(created);
    }

    private static String tableOf(Class<?> entity) {
        Table table = entity.getAnnotation(Table.class);
        assertThat(table).as("%s declares its table", entity.getSimpleName()).isNotNull();
        return table.name();
    }

    /**
     * The column of every persistent field.
     *
     * <p>Every field is required to name its column, so the mapping cannot silently depend on
     * a naming strategy that the database does not share.
     */
    private static Set<String> mappedColumnsOf(Class<?> entity) {
        Set<String> columns = new LinkedHashSet<>();
        for (Field field : entity.getDeclaredFields()) {
            if (field.isSynthetic() || Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            Column column = field.getAnnotation(Column.class);
            assertThat(column)
                    .as("%s.%s names its column", entity.getSimpleName(), field.getName())
                    .isNotNull();
            columns.add(column.name());
        }
        return columns;
    }

    /**
     * The columns of one {@code CREATE TABLE} of a migration.
     *
     * <p>Constraint and key lines of the block are not columns, so they are skipped instead of
     * being read as one.
     */
    private static Set<String> columnsOf(String migration, String table) {
        String marker = "CREATE TABLE IF NOT EXISTS " + table + " (";
        int start = migration.indexOf(marker);
        assertThat(start).as("the migration creates table %s", table).isGreaterThanOrEqualTo(0);

        String body = migration.substring(start + marker.length());
        int end = body.indexOf("\n);");
        assertThat(end).as("the CREATE TABLE of %s is closed", table).isGreaterThan(0);

        Set<String> columns = new LinkedHashSet<>();
        for (String line : body.substring(0, end).split("\n")) {
            String declaration = line.strip();
            // A column starts with its name; the keys and constraints of the block - which can
            // continue on a second line, like `FOREIGN KEY ...` - start with a keyword.
            if (declaration.matches("[a-z][a-z0-9_]*\\s+\\S+.*")) {
                columns.add(declaration.split("\\s+")[0]);
            }
        }
        return columns;
    }

    private static String migrationOf(String file) throws IOException {
        Path path = Path.of("..", "stream-processor", "src", "main", "resources", "db", "migration", file);
        assertThat(path)
                .as("the migration belongs to the stream processor: %s", path.toAbsolutePath())
                .exists();
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
