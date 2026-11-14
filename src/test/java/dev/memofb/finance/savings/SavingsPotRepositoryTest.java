package dev.memofb.finance.savings;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.stream.Stream;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class SavingsPotRepositoryTest {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    static JdbcTemplate jdbc;
    static SavingsPotRepository pots;
    static TransactionTemplate transactions;

    @BeforeAll
    static void database() {
        var dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        pots = new SavingsPotRepository(jdbc);
        transactions = new TransactionTemplate(new JdbcTransactionManager(dataSource));
        Stream.of("owner@example.com", "other@example.com").forEach(email ->
                jdbc.update("INSERT INTO users (username, password, enabled) VALUES (?, 'test-only', true)", email));
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM savings_pots");
    }

    @Test
    void scopesReadsAndWritesToOwnerAndPreservesDeletedData() {
        var first = pots.create("owner@example.com", "Same name", new BigDecimal("100.00"), "#AABBCC");
        var second = pots.create("owner@example.com", "Same name", new BigDecimal("200.00"), "#AABBCC");
        var other = pots.create("other@example.com", "Other", BigDecimal.ONE, "#FFFFFF");
        assertThat(first.balance()).isZero();
        assertThat(pots.listActive("owner@example.com")).extracting(SavingsPot::id).containsExactly(first.id(), second.id());
        assertThat(pots.findActive("other@example.com", first.id())).isEmpty();
        assertThat(pots.lock("other@example.com", first.id())).isEmpty();

        transactions.executeWithoutResult(status -> {
            assertThat(pots.lock("owner@example.com", first.id())).contains(first);
            pots.updateDetails("owner@example.com", first.id(), "Updated", new BigDecimal("50.00"), "#000000");
            pots.updateBalance("owner@example.com", first.id(), new BigDecimal("12.30"));
            pots.updateDeletedAt("owner@example.com", first.id(), Instant.parse("2026-11-14T00:00:00Z"));
        });

        assertThat(pots.findActive("owner@example.com", first.id())).isEmpty();
        assertThat(pots.listActive("owner@example.com")).extracting(SavingsPot::id).containsExactly(second.id());
        var deleted = pots.lock("owner@example.com", first.id()).orElseThrow();
        assertThat(deleted.name()).isEqualTo("Updated");
        assertThat(deleted.balance()).isEqualByComparingTo("12.30");
        assertThat(deleted.deletedAt()).isEqualTo(Instant.parse("2026-11-14T00:00:00Z"));
        assertThat(pots.findActive("other@example.com", other.id())).contains(other);
        var restored = pots.updateDeletedAt("owner@example.com", first.id(), null);
        assertThat(restored.deletedAt()).isNull();
        assertThat(restored.balance()).isEqualByComparingTo(deleted.balance());
    }

    @ParameterizedTest
    @ValueSource(strings = {"balance = -0.01", "balance = 'NaN'", "target_amount = 0", "target_amount = 'NaN'",
            "color = '#aabbcc'", "color = 'invalid'", "name = ''", "name = ' padded '"})
    void databaseRejectsInvalidValues(String assignment) {
        var pot = pots.create("owner@example.com", "Valid", BigDecimal.ONE, "#FFFFFF");
        assertThatThrownBy(() -> jdbc.update("UPDATE savings_pots SET " + assignment + " WHERE id = ?", pot.id()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(pots.findActive("owner@example.com", pot.id())).contains(pot);
    }

    @Test
    void requiresAnExistingOwner() {
        assertThatThrownBy(() -> pots.create("missing@example.com", "Invalid", BigDecimal.ONE, "#FFFFFF"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM savings_pots", Long.class)).isZero();
    }
}
