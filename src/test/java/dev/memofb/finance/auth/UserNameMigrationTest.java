package dev.memofb.finance.auth;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class UserNameMigrationTest {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @Test
    void preservesExistingAccountsWhenAddingNamesAndRemovingAuthorities() {
        var dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(dataSource).schemas("legacy").defaultSchema("legacy")
                .target("1").load().migrate();
        var jdbc = new JdbcTemplate(dataSource);
        var passwords = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        var hash = passwords.encode("migration-test-passphrase");
        jdbc.update("INSERT INTO legacy.users (username, password, enabled) VALUES (?, ?, ?)",
                "legacy@example.com", hash, true);
        jdbc.update("INSERT INTO legacy.authorities (username, authority) VALUES (?, ?)",
                "legacy@example.com", "ROLE_USER");

        Flyway.configure().dataSource(dataSource).schemas("legacy").defaultSchema("legacy")
                .target("2").load().migrate();

        var user = jdbc.queryForMap("SELECT * FROM legacy.users WHERE username = ?", "legacy@example.com");
        assertThat(user).containsEntry("username", "legacy@example.com")
                .containsEntry("name", null).containsEntry("enabled", true).containsEntry("password", hash);
        assertThat(passwords.matches("migration-test-passphrase", (String) user.get("password"))).isTrue();
        assertThat(jdbc.queryForList("SELECT authority FROM legacy.authorities WHERE username = ?",
                String.class, "legacy@example.com")).containsExactly("ROLE_USER");
        jdbc.update("INSERT INTO legacy.users (username, password, enabled, name) VALUES (?, ?, false, ?)",
                "disabled@example.com", hash, "Nombre existente");
        var disabled = jdbc.queryForMap("SELECT * FROM legacy.users WHERE username = ?", "disabled@example.com");

        Flyway.configure().dataSource(dataSource).schemas("legacy").defaultSchema("legacy")
                .target("3").load().migrate();

        assertThat(jdbc.queryForObject("SELECT to_regclass('legacy.authorities')", String.class)).isNull();
        assertThat(jdbc.queryForMap("SELECT * FROM legacy.users WHERE username = ?", "legacy@example.com"))
                .isEqualTo(user);
        assertThat(jdbc.queryForMap("SELECT * FROM legacy.users WHERE username = ?", "disabled@example.com"))
                .isEqualTo(disabled);
        dataSource.setSchema("legacy");
        var migrated = new SecurityConfiguration().users(jdbc).loadUserByUsername("LEGACY@EXAMPLE.COM");
        assertThat(migrated.isEnabled()).isTrue();
        assertThat(migrated.getAuthorities()).isEmpty();
        assertThat(passwords.matches("migration-test-passphrase", migrated.getPassword())).isTrue();
    }
}
