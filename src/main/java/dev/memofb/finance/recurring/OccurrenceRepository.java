package dev.memofb.finance.recurring;

import java.time.*;
import java.util.Optional;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;

@Repository
public class OccurrenceRepository {
    private static final RowMapper<PaymentOccurrence> ROW = (r,i)->{
        var paid=r.getObject("paid_at",OffsetDateTime.class);
        return new PaymentOccurrence(r.getLong("id"),r.getLong("recurring_payment_id"),r.getInt("sequence_number"),
                r.getObject("due_date",LocalDate.class),r.getString("status"),paid==null?null:paid.toInstant());
    };
    private final JdbcTemplate jdbc;
    public OccurrenceRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public Optional<PaymentOccurrence> find(String owner,long series,YearMonth month) {
        return jdbc.query("SELECT * FROM recurring_payment_occurrences WHERE username=? AND recurring_payment_id=? AND due_month=?",
                ROW,owner,series,month.atDay(1)).stream().findFirst();
    }
    PaymentOccurrence create(String owner,long series,int sequence,LocalDate dueDate) {
        return jdbc.queryForObject("""
                INSERT INTO recurring_payment_occurrences(username,recurring_payment_id,sequence_number,due_month,due_date,status)
                VALUES (?,?,?,?,?,'VOID') RETURNING *
                """,ROW,owner,series,sequence,YearMonth.from(dueDate).atDay(1),dueDate);
    }
    int highestSequence(String owner,long series) {
        return jdbc.queryForObject("SELECT COALESCE(max(sequence_number),0) FROM recurring_payment_occurrences WHERE username=? AND recurring_payment_id=?",
                Integer.class,owner,series);
    }
    public void reconcile(String owner,long id,Instant now) {
        boolean paid=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM transactions WHERE username=? AND recurring_occurrence_id=? AND deleted_at IS NULL AND amount<0)",
                Boolean.class,owner,id);
        if(paid) jdbc.update("UPDATE recurring_payment_occurrences SET status='PAID',paid_at=COALESCE(paid_at,?) WHERE username=? AND id=?",now.atOffset(ZoneOffset.UTC),owner,id);
        else jdbc.update("UPDATE recurring_payment_occurrences SET status='VOID',paid_at=NULL WHERE username=? AND id=?",owner,id);
    }
}
