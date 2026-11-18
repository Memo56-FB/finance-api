package dev.memofb.finance.recurring;

import java.time.*;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import dev.memofb.finance.budgets.BudgetRepository;
import dev.memofb.finance.categories.CategoryRepository;
import dev.memofb.finance.shared.*;
import dev.memofb.finance.transactions.TransactionRepository;

@Service
public class RecurringService {
    private final RecurringRepository payments;
    private final OccurrenceRepository occurrences;
    private final TransactionRepository transactions;
    private final BudgetRepository budgets;
    private final CategoryRepository categories;
    private final FinancialLock lock;
    private final BusinessCalendar calendar;
    private final Clock clock;
    RecurringService(RecurringRepository payments,OccurrenceRepository occurrences,TransactionRepository transactions,
            BudgetRepository budgets,CategoryRepository categories,FinancialLock lock,BusinessCalendar calendar,Clock clock) {
        this.payments=payments;this.occurrences=occurrences;this.transactions=transactions;this.budgets=budgets;
        this.categories=categories;this.lock=lock;this.calendar=calendar;this.clock=clock;
    }
    public RecurringPaymentResponse get(String owner,long id,YearMonth month) { return payments.get(owner,id,month); }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public PageResponse<RecurringPaymentResponse> list(String owner,YearMonth month,ListQuery query) { return payments.list(owner,month,query); }
    public PaymentSummaryResponse summary(String owner,YearMonth month) { return payments.summary(owner,month,calendar.today()); }
    @Transactional
    public RecurringPaymentResponse create(String owner,RecurringPaymentRequest request) {
        lock.acquire(owner); categories.requireAssignment(owner,request.categoryId(),null);
        return payments.get(owner,payments.create(owner,request),calendar.month());
    }
    @Transactional
    public RecurringPaymentResponse update(String owner,long id,RecurringPaymentRequest request) {
        lock.acquire(owner);var old=payments.lockActive(owner,id).orElseThrow(FinancialException::notFound);
        categories.requireAssignment(owner,request.categoryId(),null);
        int highest=occurrences.highestSequence(owner,id);
        if(highest>0 && !YearMonth.from(old.firstDueDate()).equals(YearMonth.from(request.firstDueDate()))) {
            throw FinancialException.conflict("The first due month cannot change after a payment has been recorded");
        }
        if(request.totalInstallments()!=null && request.totalInstallments()<highest) {
            throw FinancialException.conflict("The installment total cannot be smaller than the highest recorded installment");
        }
        payments.update(owner,id,request); return payments.get(owner,id,calendar.month());
    }
    @Transactional
    public void delete(String owner,long id) {
        lock.acquire(owner);payments.lockActive(owner,id).orElseThrow(FinancialException::notFound);payments.delete(owner,id);
    }
    @Transactional
    public PaymentRecordedResponse pay(String owner,long id,YearMonth month) {
        lock.acquire(owner);var series=payments.lockActive(owner,id).orElseThrow(FinancialException::notFound);
        var occurrence=record(owner,series,month,true);
        var expense=transactions.activeExpense(owner,occurrence.id()).orElseThrow();
        return new PaymentRecordedResponse(occurrence.id(),id,month,"PAID",occurrence.paidAt(),expense,
                budgets.warnings(owner,expense.categoryId(),expense.transactionDate()));
    }
    @Transactional
    public boolean processNext(String owner,long id) {
        lock.acquire(owner);var candidate=payments.lockActive(owner,id);
        if(candidate.isEmpty()) return false;
        var series=candidate.get();var sequence=series.nextAutoSequence();
        if(series.totalInstallments()!=null && sequence>series.totalInstallments()) return false;
        var due=series.dueDate(sequence);
        if(due.isAfter(calendar.today())) return false;
        record(owner,series,YearMonth.from(due),false);payments.advance(owner,id);return true;
    }
    private PaymentOccurrence record(String owner,RecurringPayment series,YearMonth month,boolean manual) {
        int sequence=series.sequence(month);
        var existing=occurrences.find(owner,series.id(),month);
        if(existing.isPresent() && (!manual || existing.get().status().equals("PAID"))) return existing.get();
        var occurrence=existing.orElseGet(()->occurrences.create(owner,series.id(),sequence,series.dueDate(sequence)));
        transactions.insert(owner,series.name(),series.categoryId(),manual?calendar.today():occurrence.dueDate(),
                series.amount().negate(),"RECURRING_PAYMENT",null,occurrence.id());
        occurrences.reconcile(owner,occurrence.id(),clock.instant());
        return occurrences.find(owner,series.id(),month).orElseThrow();
    }
}
