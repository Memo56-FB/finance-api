package dev.memofb.finance.budgets;

import java.time.YearMonth;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import dev.memofb.finance.categories.CategoryRepository;
import dev.memofb.finance.shared.*;

@Service
class BudgetService {
    private final BudgetRepository budgets;
    private final CategoryRepository categories;
    private final FinancialLock lock;
    private final BusinessCalendar calendar;
    BudgetService(BudgetRepository budgets,CategoryRepository categories,FinancialLock lock,BusinessCalendar calendar) {
        this.budgets=budgets; this.categories=categories; this.lock=lock; this.calendar=calendar;
    }
    public List<BudgetResponse> list(String owner,YearMonth month) { return budgets.list(owner,month); }
    public BudgetResponse get(String owner,long id,YearMonth month) { return budgets.get(owner,id,month); }
    @Transactional
    public BudgetResponse create(String owner,BudgetRequest request) {
        lock.acquire(owner); categories.requireAssignment(owner,request.categoryId(),null);
        return budgets.get(owner,budgets.create(owner,request),calendar.month());
    }
    @Transactional
    public BudgetResponse update(String owner,long id,BudgetRequest request) {
        lock.acquire(owner); budgets.get(owner,id,calendar.month()); categories.requireAssignment(owner,request.categoryId(),null);
        budgets.update(owner,id,request); return budgets.get(owner,id,calendar.month());
    }
    @Transactional
    public void delete(String owner,long id) { lock.acquire(owner); budgets.get(owner,id,calendar.month()); budgets.delete(owner,id); }
}
