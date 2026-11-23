package dev.memofb.finance.auth;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class LoginUsernameMigrationTest {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @Test
    void assignsDistinctLoginsAndKeepsCredentialsAndFinancialOwnership() {
        var ds = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(ds).target("6").load().migrate();
        var jdbc = new JdbcTemplate(ds);
        jdbc.update("""
                INSERT INTO users(username, password, enabled) VALUES
                ('sam','existing-hash',true), ('sam@a.test','first-hash',false),
                ('sam@b.test','second-hash',true), ('a+b@test','other-hash',true)
                """);
        String owner = "sam@a.test";
        long pot = jdbc.queryForObject("""
                INSERT INTO savings_pots(username,name,target_amount,balance,color)
                VALUES (?,'Car',50,10,'#ABCDEF') RETURNING id
                """, Long.class, owner);
        long cat = jdbc.queryForObject("INSERT INTO categories(username,name) VALUES (?,'Bills') RETURNING id", Long.class, owner);
        jdbc.update("INSERT INTO budgets(username,category_id,spending_limit,color) VALUES (?,?,30,'#ABCDEF')", owner, cat);
        long series = jdbc.queryForObject("""
                INSERT INTO recurring_payments(username,name,category_id,type,amount,first_due_date)
                VALUES (?,'Bill',?,'SUBSCRIPTION',3,'2026-01-01') RETURNING id
                """, Long.class, owner, cat);
        long receipt = jdbc.queryForObject("""
                INSERT INTO recurring_payment_occurrences(username,recurring_payment_id,sequence_number,due_month,due_date,status)
                VALUES (?,?,1,'2026-01-01','2026-01-01','VOID') RETURNING id
                """, Long.class, owner, series);
        jdbc.update("""
                INSERT INTO transactions(username,name,category_id,pot_id,recurring_occurrence_id,transaction_date,amount,source_type)
                VALUES (?,'Car',NULL,?,NULL,CURRENT_DATE,10,'POT'), (?,'Bill',?,NULL,?,CURRENT_DATE,3,'RECURRING_PAYMENT')
                """, owner, pot, owner, cat, receipt);
        jdbc.update("INSERT INTO financial_summary_preferences VALUES (?,'MONTHLY',5)", owner);

        Flyway.configure().dataSource(ds).load().migrate();
        assertThat(jdbc.queryForList("SELECT username FROM users ORDER BY username", String.class))
                .containsExactly("a_b", "sam", "sam_2", "sam_3");
        var users = new SecurityConfiguration().users(jdbc);
        assertThat(users.loadUserByUsername("SAM_2").isEnabled()).isFalse();
        assertThat(users.loadUserByUsername("sam_2").getPassword()).isEqualTo("first-hash");
        assertThat(users.loadUserByUsername("sam_3").getPassword()).isEqualTo("second-hash");
        for (var table : new String[]{"savings_pots", "categories", "budgets", "recurring_payments",
                "recurring_payment_occurrences", "financial_summary_preferences"}) {
            assertThat(jdbc.queryForList("SELECT username FROM " + table, String.class)).containsExactly("sam_2");
        }
        assertThat(jdbc.queryForList("SELECT username FROM effective_transactions", String.class))
                .containsExactly("sam_2", "sam_2");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO users VALUES (?, 'unused', true, NULL)", owner))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
