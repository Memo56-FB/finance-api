package dev.memofb.finance.savings;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class SavingsPotRepository {

    private static final String COLUMNS = "id, name, target_amount, balance, color, deleted_at";
    private static final RowMapper<SavingsPot> ROW = (row, index) -> {
        var deletedAt = row.getObject("deleted_at", OffsetDateTime.class);
        return new SavingsPot(row.getLong("id"), row.getString("name"), row.getBigDecimal("target_amount"),
                row.getBigDecimal("balance"), row.getString("color"), deletedAt == null ? null : deletedAt.toInstant());
    };

    private final JdbcTemplate jdbc;

    SavingsPotRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    SavingsPot create(String username, String name, BigDecimal targetAmount, String color) {
        return jdbc.queryForObject("INSERT INTO savings_pots (username, name, target_amount, color) VALUES (?, ?, ?, ?) "
                + "RETURNING " + COLUMNS, ROW, username, name, targetAmount, color);
    }

    List<SavingsPot> listActive(String username) {
        return jdbc.query("SELECT " + COLUMNS + " FROM savings_pots WHERE username = ? AND deleted_at IS NULL ORDER BY id",
                ROW, username);
    }

    List<SavingsPot> balancesBefore(String username, LocalDate endExclusive) {
        return jdbc.query("""
                SELECT p.id, p.name, p.target_amount, sum(t.amount) AS balance, p.color, p.deleted_at
                FROM savings_pots p
                JOIN transactions t ON t.username = p.username AND t.pot_id = p.id
                WHERE p.username = ? AND p.deleted_at IS NULL AND t.deleted_at IS NULL AND t.transaction_date < ?
                GROUP BY p.id ORDER BY p.id
                """, ROW, username, endExclusive);
    }

    Optional<SavingsPot> findActive(String username, long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM savings_pots WHERE username = ? AND id = ? AND deleted_at IS NULL",
                ROW, username, id).stream().findFirst();
    }

    public Optional<SavingsPot> lock(String username, long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM savings_pots WHERE username = ? AND id = ? FOR UPDATE",
                ROW, username, id).stream().findFirst();
    }

    SavingsPot updateDetails(String username, long id, String name, BigDecimal targetAmount, String color) {
        return jdbc.queryForObject("UPDATE savings_pots SET name = ?, target_amount = ?, color = ? "
                + "WHERE username = ? AND id = ? AND deleted_at IS NULL RETURNING " + COLUMNS,
                ROW, name, targetAmount, color, username, id);
    }

    public SavingsPot updateBalance(String username, long id, BigDecimal balance) {
        return jdbc.queryForObject("UPDATE savings_pots SET balance = ? WHERE username = ? AND id = ? "
                + "AND deleted_at IS NULL RETURNING " + COLUMNS, ROW, balance, username, id);
    }

    SavingsPot updateDeletedAt(String username, long id, Instant deletedAt) {
        return jdbc.queryForObject("UPDATE savings_pots SET deleted_at = ? WHERE username = ? AND id = ? RETURNING " + COLUMNS,
                ROW, deletedAt == null ? null : deletedAt.atOffset(ZoneOffset.UTC), username, id);
    }
}
