package dev.memofb.finance.savings;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class SavingsPotMigrationTest {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @Test
    void migratesEmptyDatabaseAndPreservesExistingUsersFromV3() {
        var dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var jdbc = new JdbcTemplate(dataSource);
        Flyway.configure().dataSource(dataSource).schemas("fresh").defaultSchema("fresh").load().migrate();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fresh.savings_pots", Long.class)).isZero();

        Flyway.configure().dataSource(dataSource).schemas("existing").defaultSchema("existing")
                .target("3").load().migrate();
        jdbc.update("INSERT INTO existing.users (username, password, enabled, name) VALUES (?, ?, false, ?)",
                "existing@example.com", "unchanged-test-hash", "Existing user");
        var before = jdbc.queryForMap("SELECT * FROM existing.users");

        Flyway.configure().dataSource(dataSource).schemas("existing").defaultSchema("existing").load().migrate();

        assertThat(jdbc.queryForMap("SELECT * FROM existing.users")).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM existing.savings_pots", Long.class)).isZero();
    }
}
