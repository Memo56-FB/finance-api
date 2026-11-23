package dev.memofb.finance.shared;

import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class FinancialMigrationTest {
    @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @Test
    void upgradesV4WithoutInventingHistoryAndEnforcesOwnershipAndMoney() {
        var ds = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var jdbc = new JdbcTemplate(ds);
        var flyway = Flyway.configure().dataSource(ds).target("4").load();
        flyway.migrate();
        for (var owner : new String[]{"owner@example.com", "other@example.com"}) {
            jdbc.update("INSERT INTO users(username,password,enabled) VALUES (?, 'test-only',true)", owner);
        }
        jdbc.update("""
                INSERT INTO savings_pots(username,name,target_amount,balance,color,deleted_at) VALUES
                ('owner@example.com','Active',500,250,'#FFFFFF',null),
                ('owner@example.com','Deleted',500,30,'#FFFFFF',CURRENT_TIMESTAMP),
                ('owner@example.com','Empty',500,0,'#FFFFFF',null)
                """);
        var before = jdbc.queryForList("SELECT * FROM savings_pots ORDER BY id");
        var users = jdbc.queryForList("SELECT * FROM users ORDER BY username");
        flyway = Flyway.configure().dataSource(ds).target("5").load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForList("SELECT * FROM savings_pots ORDER BY id")).isEqualTo(before);
        assertThat(jdbc.queryForList("SELECT * FROM users ORDER BY username")).isEqualTo(users);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transactions WHERE opening_balance", Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT sum(amount) FROM effective_transactions", java.math.BigDecimal.class)).isEqualByComparingTo("250");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_summary_preferences", Long.class)).isZero();
        var category = jdbc.queryForObject("INSERT INTO categories(username,name) VALUES ('owner@example.com','Food') RETURNING id", Long.class);
        rejects(jdbc,"INSERT INTO budgets(username,category_id,spending_limit,color) VALUES ('other@example.com',"+category+",1,'#FFFFFF')");
        rejects(jdbc,"INSERT INTO financial_summary_preferences VALUES ('owner@example.com','MONTHLY',32)");
        rejects(jdbc,"INSERT INTO financial_summary_preferences VALUES ('owner@example.com','WEEKLY',1)");
        jdbc.update("INSERT INTO financial_summary_preferences VALUES ('owner@example.com','MONTHLY',1)");
        rejects(jdbc,"INSERT INTO financial_summary_preferences VALUES ('owner@example.com','SEMIMONTHLY',4)");
        jdbc.update("INSERT INTO budgets(username,category_id,spending_limit,color) VALUES ('owner@example.com',?,100,'#FFFFFF')",category);
        rejects(jdbc,"INSERT INTO budgets(username,category_id,spending_limit,color) VALUES ('owner@example.com',"+category+",100,'#FFFFFF')");
        for(var amount : new String[]{"0","'NaN'","'Infinity'","100000000000000000"}) {
            rejects(jdbc,"INSERT INTO transactions(username,name,category_id,transaction_date,amount,source_type) VALUES ('owner@example.com','Bad',"+category+",CURRENT_DATE,"+amount+",'MANUAL')");
        }
        var series=jdbc.queryForObject("""
                INSERT INTO recurring_payments(username,name,category_id,type,amount,first_due_date)
                VALUES ('owner@example.com','Bill',?,'SUBSCRIPTION',10,'2027-01-04') RETURNING id
                """,Long.class,category);
        var occurrence=jdbc.queryForObject("""
                INSERT INTO recurring_payment_occurrences(username,recurring_payment_id,sequence_number,due_month,due_date,status,paid_at)
                VALUES ('owner@example.com',?,1,'2027-01-01','2027-01-04','PAID',CURRENT_TIMESTAMP) RETURNING id
                """,Long.class,series);
        var expense="INSERT INTO transactions(username,name,category_id,transaction_date,amount,source_type,recurring_occurrence_id) VALUES ('owner@example.com','Bill',"+category+",'2027-01-04',-10,'RECURRING_PAYMENT',"+occurrence+")";
        jdbc.update(expense);
        rejects(jdbc,expense);
        rejects(jdbc,"UPDATE recurring_payment_occurrences SET status='VOID' WHERE id="+occurrence);
        Flyway.configure().dataSource(ds).schemas("fresh").defaultSchema("fresh").load().migrate();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fresh.transactions",Long.class)).isZero();
    }

    private void rejects(JdbcTemplate jdbc, String sql) {
        assertThatThrownBy(()->jdbc.update(sql)).isInstanceOf(DataIntegrityViolationException.class);
    }
}
