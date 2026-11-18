package dev.memofb.finance.budgets;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import dev.memofb.finance.shared.FinancialException;

@Repository
public class BudgetRepository {
    private final JdbcTemplate jdbc;
    public BudgetRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    public List<BudgetResponse> list(String owner, YearMonth month) {
        return jdbc.query("""
                SELECT b.id,b.category_id,c.name,b.spending_limit,b.color,COALESCE(s.spent,0) AS spent
                FROM budgets b JOIN categories c ON c.id=b.category_id AND c.username=b.username
                LEFT JOIN (SELECT category_id,sum(-amount) AS spent FROM effective_transactions
                    WHERE username=? AND transaction_date>=? AND transaction_date<? AND amount<0 GROUP BY category_id) s
                    ON s.category_id=b.category_id
                WHERE b.username=? AND b.deleted_at IS NULL ORDER BY b.id
                """,(r,i)->{
                    var limit=r.getBigDecimal("spending_limit"); var spent=r.getBigDecimal("spent");
                    return new BudgetResponse(r.getLong("id"),r.getLong("category_id"),r.getString("name"),limit,r.getString("color"),
                            month,spent,limit.subtract(spent),spent.compareTo(limit)>=0,spent.subtract(limit).max(BigDecimal.ZERO));
                },owner,month.atDay(1),month.plusMonths(1).atDay(1),owner);
    }

    public List<BudgetWarning> warnings(String owner, Long category, LocalDate date) {
        if(category==null) return List.of();
        return list(owner,YearMonth.from(date)).stream().filter(b->b.categoryId()==category && b.limitReached())
                .map(b->new BudgetWarning(b.id(),b.categoryId(),b.month(),true,b.exceededAmount())).toList();
    }

    BudgetResponse get(String owner,long id,YearMonth month) {
        return list(owner,month).stream().filter(b->b.id()==id).findFirst().orElseThrow(FinancialException::notFound);
    }
    long create(String owner,BudgetRequest request) {
        return jdbc.queryForObject("INSERT INTO budgets(username,category_id,spending_limit,color) VALUES (?,?,?,?) RETURNING id",
                Long.class,owner,request.categoryId(),request.spendingLimit(),request.color());
    }
    void update(String owner,long id,BudgetRequest request) {
        jdbc.update("UPDATE budgets SET category_id=?,spending_limit=?,color=? WHERE username=? AND id=?",
                request.categoryId(),request.spendingLimit(),request.color(),owner,id);
    }
    void delete(String owner,long id) { jdbc.update("UPDATE budgets SET deleted_at=CURRENT_TIMESTAMP WHERE username=? AND id=?",owner,id); }
}
