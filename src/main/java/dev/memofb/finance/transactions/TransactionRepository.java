package dev.memofb.finance.transactions;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;
import dev.memofb.finance.shared.*;

@Repository
public class TransactionRepository {
    private static final String SELECT = """
            SELECT t.id,t.name,t.category_id,c.name AS category_name,t.transaction_date,t.amount,t.source_type,
                t.pot_id,p.name AS pot_name,o.recurring_payment_id,t.recurring_occurrence_id,t.opening_balance
            FROM effective_transactions t
            LEFT JOIN categories c ON c.id=t.category_id AND c.username=t.username
            LEFT JOIN savings_pots p ON p.id=t.pot_id AND p.username=t.username
            LEFT JOIN recurring_payment_occurrences o ON o.id=t.recurring_occurrence_id AND o.username=t.username
            WHERE t.username=?
            """;
    private static final RowMapper<TransactionResponse> ROW = (r,i)->new TransactionResponse(r.getLong("id"),r.getString("name"),
            r.getObject("category_id",Long.class),r.getString("category_name"),r.getObject("transaction_date",LocalDate.class),
            r.getBigDecimal("amount"),r.getString("source_type"),r.getObject("pot_id",Long.class),r.getString("pot_name"),
            r.getObject("recurring_payment_id",Long.class),r.getObject("recurring_occurrence_id",Long.class),r.getBoolean("opening_balance"),List.of());
    private final JdbcTemplate jdbc;
    public TransactionRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    public TransactionResponse get(String owner,long id) {
        return jdbc.query(SELECT+" AND t.id=?",ROW,owner,id).stream().findFirst().orElseThrow(FinancialException::notFound);
    }
    public Optional<TransactionResponse> activeExpense(String owner,long occurrenceId) {
        return jdbc.query(SELECT+" AND t.recurring_occurrence_id=? AND t.amount<0",ROW,owner,occurrenceId).stream().findFirst();
    }
    PageResponse<TransactionResponse> list(String owner,ListQuery query,Long categoryId) {
        var filter=" AND t.name ILIKE ? ESCAPE '!'";
        var args=new ArrayList<Object>(List.of(owner,query.pattern()));
        if(categoryId!=null) { filter+=" AND t.category_id=?";args.add(categoryId); }
        var total=jdbc.queryForObject("SELECT count(*) FROM effective_transactions t WHERE t.username=?"+filter,
                Long.class,args.toArray());
        args.add(query.size());args.add(query.offset());
        var rows=jdbc.query("SELECT * FROM ("+SELECT+filter+") history ORDER BY "+query.orderBy("transaction_date")+" LIMIT ? OFFSET ?",
                ROW,args.toArray());
        return PageResponse.of(rows,query.page(),query.size(),total);
    }
    public long insert(String owner,String name,Long category,LocalDate date,BigDecimal amount,String source,Long pot,Long occurrence) {
        return jdbc.queryForObject("""
                INSERT INTO transactions(username,name,category_id,transaction_date,amount,source_type,pot_id,recurring_occurrence_id)
                VALUES (?,?,?,?,?,?,?,?) RETURNING id
                """,Long.class,owner,name,category,date,amount,source,pot,occurrence);
    }
    public void potMovement(String owner,long pot,String name,LocalDate date,BigDecimal amount) {
        insert(owner,name,null,date,amount,"POT",pot,null);
    }
    void update(String owner,long id,TransactionRequest request) {
        jdbc.update("UPDATE transactions SET name=?,category_id=?,transaction_date=?,amount=? WHERE username=? AND id=?",
                request.name(),request.categoryId(),request.transactionDate(),request.amount(),owner,id);
    }
    void delete(String owner,long id) { jdbc.update("UPDATE transactions SET deleted_at=CURRENT_TIMESTAMP WHERE username=? AND id=?",owner,id); }
}
