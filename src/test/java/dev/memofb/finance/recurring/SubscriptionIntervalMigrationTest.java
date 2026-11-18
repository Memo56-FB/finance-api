package dev.memofb.finance.recurring;

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
class SubscriptionIntervalMigrationTest {
    @Container static final PostgreSQLContainer postgres=new PostgreSQLContainer("postgres:18-alpine");

    @Test
    void upgradesExistingMonthlyPaymentsWithoutChangingHistoryOrCursor() {
        var dataSource=new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
        var jdbc=new JdbcTemplate(dataSource);
        Flyway.configure().dataSource(dataSource).target("5").load().migrate();
        jdbc.update("INSERT INTO users(username,password,enabled) VALUES ('owner@example.com','test-only',true)");
        long category=jdbc.queryForObject("INSERT INTO categories(username,name) VALUES ('owner@example.com','Bills') RETURNING id",Long.class);
        long subscription=jdbc.queryForObject("""
                INSERT INTO recurring_payments(username,name,category_id,type,amount,first_due_date,next_auto_sequence)
                VALUES ('owner@example.com','Subscription',?,'SUBSCRIPTION',20,'2027-01-31',3) RETURNING id
                """,Long.class,category);
        long installment=jdbc.queryForObject("""
                INSERT INTO recurring_payments(username,name,category_id,type,amount,first_due_date,total_installments)
                VALUES ('owner@example.com','Installment',?,'INSTALLMENT',10,'2027-01-05',12) RETURNING id
                """,Long.class,category);
        long occurrence=jdbc.queryForObject("""
                INSERT INTO recurring_payment_occurrences(username,recurring_payment_id,sequence_number,due_month,due_date,status,paid_at)
                VALUES ('owner@example.com',?,2,'2027-02-01','2027-02-28','PAID',CURRENT_TIMESTAMP) RETURNING id
                """,Long.class,subscription);
        jdbc.update("""
                INSERT INTO transactions(username,name,category_id,transaction_date,amount,source_type,recurring_occurrence_id)
                VALUES ('owner@example.com','Subscription',?,'2027-02-28',-20,'RECURRING_PAYMENT',?)
                """,category,occurrence);
        var payments=jdbc.queryForList("SELECT * FROM recurring_payments ORDER BY id");
        var occurrences=jdbc.queryForList("SELECT * FROM recurring_payment_occurrences");
        var transactions=jdbc.queryForList("SELECT * FROM transactions");
        var flyway=Flyway.configure().dataSource(dataSource).load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        var migrated=jdbc.queryForList("SELECT * FROM recurring_payments ORDER BY id");
        for(int i=0;i<payments.size();i++) {
            assertThat(migrated.get(i)).containsAllEntriesOf(payments.get(i));
            assertThat(((Number)migrated.get(i).get("interval_months")).intValue()).isEqualTo(1);
        }
        assertThat(jdbc.queryForList("SELECT * FROM recurring_payment_occurrences")).isEqualTo(occurrences);
        assertThat(jdbc.queryForList("SELECT * FROM transactions")).isEqualTo(transactions);
        for(int invalid:new int[]{0,-1,13}) {
            assertThatThrownBy(()->jdbc.update("UPDATE recurring_payments SET interval_months=? WHERE id=?",invalid,subscription))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThatThrownBy(()->jdbc.update("UPDATE recurring_payments SET interval_months=2 WHERE id=?",installment))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("UPDATE recurring_payments SET interval_months=12 WHERE id=?",subscription);
    }
}
