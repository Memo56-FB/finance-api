package dev.memofb.finance.recurring;

import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;
import dev.memofb.finance.shared.*;

@Repository
class RecurringRepository {
    private static final RowMapper<RecurringPayment> RAW=(r,i)->new RecurringPayment(r.getLong("id"),r.getString("name"),
            r.getLong("category_id"),PaymentType.valueOf(r.getString("type")),r.getBigDecimal("amount"),r.getObject("first_due_date",LocalDate.class),
            r.getObject("total_installments",Integer.class),r.getInt("next_auto_sequence"));
    private static final String MONTHLY="""
            WITH params AS (SELECT ?::date AS month_start), progress AS (
                SELECT o.recurring_payment_id,count(*) AS paid_count FROM recurring_payment_occurrences o
                JOIN transactions t ON t.recurring_occurrence_id=o.id AND t.username=o.username AND t.deleted_at IS NULL AND t.amount<0
                WHERE o.username=? GROUP BY o.recurring_payment_id
            )
            SELECT r.*,c.name AS category_name,COALESCE(progress.paid_count,0) AS paid_count,
                CASE WHEN o.id IS NOT NULL OR (x.month_start>=date_trunc('month',r.first_due_date)::date
                    AND (r.total_installments IS NULL OR x.month_start<date_trunc('month',r.first_due_date)+r.total_installments*INTERVAL '1 month'))
                THEN COALESCE(o.due_date,x.month_start+(LEAST(EXTRACT(DAY FROM r.first_due_date)::int,
                    EXTRACT(DAY FROM (x.month_start+INTERVAL '1 month - 1 day'))::int)-1)) END AS due_date,
                COALESCE(o.status,'PENDING') AS status,o.id AS occurrence_id,-t.amount AS paid_amount,o.paid_at
            FROM recurring_payments r CROSS JOIN params x
            JOIN categories c ON c.id=r.category_id AND c.username=r.username
            LEFT JOIN recurring_payment_occurrences o ON o.recurring_payment_id=r.id AND o.username=r.username AND o.due_month=x.month_start
            LEFT JOIN transactions t ON t.recurring_occurrence_id=o.id AND t.username=o.username AND t.deleted_at IS NULL AND t.amount<0
            LEFT JOIN progress ON progress.recurring_payment_id=r.id
            WHERE r.username=?
            """;
    private final JdbcTemplate jdbc;
    RecurringRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    private RowMapper<RecurringPaymentResponse> row(YearMonth month) {
        return (r,i)->{
            var due=r.getObject("due_date",LocalDate.class); var first=r.getObject("first_due_date",LocalDate.class);
            var paid=r.getObject("paid_at",OffsetDateTime.class);
            return new RecurringPaymentResponse(r.getLong("id"),r.getString("name"),r.getLong("category_id"),r.getString("category_name"),
                    PaymentType.valueOf(r.getString("type")),r.getBigDecimal("amount"),first,first.getDayOfMonth(),
                    r.getObject("total_installments",Integer.class),r.getLong("paid_count"),month,due,due==null?"NOT_SCHEDULED":r.getString("status"),
                    r.getObject("occurrence_id",Long.class),r.getBigDecimal("paid_amount"),paid==null?null:paid.toInstant());
        };
    }
    Optional<RecurringPayment> lockActive(String owner,long id) {
        return jdbc.query("SELECT * FROM recurring_payments WHERE username=? AND id=? AND deleted_at IS NULL FOR UPDATE",RAW,owner,id).stream().findFirst();
    }
    RecurringPaymentResponse get(String owner,long id,YearMonth month) {
        return jdbc.query("SELECT * FROM ("+MONTHLY+") payments WHERE id=? AND deleted_at IS NULL",row(month),month.atDay(1),owner,owner,id)
                .stream().findFirst().orElseThrow(FinancialException::notFound);
    }
    PageResponse<RecurringPaymentResponse> list(String owner,YearMonth month,ListQuery query) {
        var sql="SELECT * FROM ("+MONTHLY+") payments WHERE deleted_at IS NULL AND due_date IS NOT NULL AND name ILIKE ? ESCAPE '!'";
        var total=jdbc.queryForObject("SELECT count(*) FROM ("+sql+") counted",Long.class,month.atDay(1),owner,owner,query.pattern());
        var rows=jdbc.query(sql+" ORDER BY "+query.orderBy("due_date")+" LIMIT ? OFFSET ?",row(month),month.atDay(1),owner,owner,
                query.pattern(),query.size(),query.offset());
        return PageResponse.of(rows,query.page(),query.size(),total);
    }
    PaymentSummaryResponse summary(String owner,YearMonth month,LocalDate today) {
        return jdbc.queryForObject("""
                SELECT count(*) FILTER(WHERE status='PAID') AS paid_count,
                    COALESCE(sum(paid_amount) FILTER(WHERE status='PAID'),0) AS paid_amount,
                    count(*) FILTER(WHERE status<>'PAID') AS pending_count,
                    COALESCE(sum(amount) FILTER(WHERE status<>'PAID'),0) AS pending_amount,
                    count(*) FILTER(WHERE status<>'PAID' AND due_date BETWEEN ? AND ?) AS soon_count,
                    COALESCE(sum(amount) FILTER(WHERE status<>'PAID' AND due_date BETWEEN ? AND ?),0) AS soon_amount
                FROM (
                """+MONTHLY+") payments WHERE due_date IS NOT NULL AND (deleted_at IS NULL OR status='PAID')",
                (r,i)->{
                    var paid=new PaymentSummaryResponse.Total(r.getLong("paid_count"),r.getBigDecimal("paid_amount"));
                    var pending=new PaymentSummaryResponse.Total(r.getLong("pending_count"),r.getBigDecimal("pending_amount"));
                    var soon=new PaymentSummaryResponse.Total(r.getLong("soon_count"),r.getBigDecimal("soon_amount"));
                    return new PaymentSummaryResponse(month,paid,pending,soon,new PaymentSummaryResponse.Total(paid.count()+pending.count(),paid.totalAmount().add(pending.totalAmount())));
                },today,today.plusDays(7),today,today.plusDays(7),month.atDay(1),owner,owner);
    }
    long create(String owner,RecurringPaymentRequest r) {
        return jdbc.queryForObject("""
                INSERT INTO recurring_payments(username,name,category_id,type,amount,first_due_date,total_installments)
                VALUES (?,?,?,?,?,?,?) RETURNING id
                """,Long.class,owner,r.name(),r.categoryId(),r.type().name(),r.amount(),r.firstDueDate(),r.totalInstallments());
    }
    void update(String owner,long id,RecurringPaymentRequest r) {
        jdbc.update("UPDATE recurring_payments SET name=?,category_id=?,type=?,amount=?,first_due_date=?,total_installments=? WHERE username=? AND id=?",
                r.name(),r.categoryId(),r.type().name(),r.amount(),r.firstDueDate(),r.totalInstallments(),owner,id);
    }
    void delete(String owner,long id) { jdbc.update("UPDATE recurring_payments SET deleted_at=CURRENT_TIMESTAMP WHERE username=? AND id=?",owner,id); }
    void advance(String owner,long id) { jdbc.update("UPDATE recurring_payments SET next_auto_sequence=next_auto_sequence+1 WHERE username=? AND id=?",owner,id); }
    List<Candidate> candidates(long afterId,LocalDate today) {
        return jdbc.query("""
                SELECT id,username FROM recurring_payments WHERE id>? AND deleted_at IS NULL AND first_due_date<=?
                AND (total_installments IS NULL OR next_auto_sequence<=total_installments) ORDER BY id LIMIT 100
                """,(r,i)->new Candidate(r.getLong("id"),r.getString("username")),afterId,today);
    }
    record Candidate(long id,String owner) { }
}
