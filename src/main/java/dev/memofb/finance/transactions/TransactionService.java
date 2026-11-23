package dev.memofb.finance.transactions;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.YearMonth;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import dev.memofb.finance.budgets.*;
import dev.memofb.finance.categories.CategoryRepository;
import dev.memofb.finance.recurring.OccurrenceRepository;
import dev.memofb.finance.savings.SavingsPotRepository;
import dev.memofb.finance.shared.*;

@Service
class TransactionService {
    private final TransactionRepository repository;
    private final CategoryRepository categories;
    private final SavingsPotRepository pots;
    private final OccurrenceRepository occurrences;
    private final BudgetRepository budgets;
    private final FinancialLock lock;
    private final Clock clock;
    TransactionService(TransactionRepository repository,CategoryRepository categories,SavingsPotRepository pots,
            OccurrenceRepository occurrences,BudgetRepository budgets,FinancialLock lock,Clock clock) {
        this.repository=repository;this.categories=categories;this.pots=pots;this.occurrences=occurrences;this.budgets=budgets;this.lock=lock;this.clock=clock;
    }
    public TransactionResponse get(String owner,long id) { return repository.get(owner,id); }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public PageResponse<TransactionResponse> list(String owner, ListQuery query, Long categoryId, YearMonth month) {
        return repository.list(owner, query, categoryId, month);
    }
    @Transactional
    public TransactionResponse create(String owner,TransactionRequest request) {
        lock.acquire(owner); categories.requireAssignment(owner,request.categoryId(),null);
        var id=repository.insert(owner,request.name(),request.categoryId(),request.transactionDate(),request.amount(),"MANUAL",null,null);
        return repository.get(owner,id).withWarnings(budgets.warnings(owner,request.categoryId(),request.transactionDate()));
    }
    @Transactional
    public TransactionResponse update(String owner,long id,TransactionRequest request) {
        lock.acquire(owner); var old=repository.get(owner,id);
        if(request.categoryId()!=null || old.potId()==null) categories.requireAssignment(owner,request.categoryId(),old.categoryId());
        adjustPot(owner,old,request.amount().subtract(old.amount()));
        repository.update(owner,id,request);
        if(old.occurrenceId()!=null) occurrences.reconcile(owner,old.occurrenceId(),clock.instant());
        var warnings=new ArrayList<>(budgets.warnings(owner,request.categoryId(),request.transactionDate()));
        if(!Objects.equals(old.categoryId(),request.categoryId()) || !YearMonth.from(old.transactionDate()).equals(YearMonth.from(request.transactionDate()))) {
            warnings.addAll(budgets.warnings(owner,old.categoryId(),old.transactionDate()));
        }
        return repository.get(owner,id).withWarnings(warnings);
    }
    @Transactional
    public void delete(String owner,long id) {
        lock.acquire(owner); var old=repository.get(owner,id);
        adjustPot(owner,old,old.amount().negate()); repository.delete(owner,id);
        if(old.occurrenceId()!=null) occurrences.reconcile(owner,old.occurrenceId(),clock.instant());
    }
    private void adjustPot(String owner,TransactionResponse old,BigDecimal delta) {
        if(old.potId()==null) return;
        var pot=pots.lock(owner,old.potId()).orElseThrow(FinancialException::notFound);
        if(pot.deletedAt()!=null) throw FinancialException.notFound();
        var balance=pot.balance().add(delta);
        if(balance.signum()<0 || balance.compareTo(new BigDecimal("99999999999999999.99"))>0) {
            throw FinancialException.conflict("The correction would exceed the savings pot balance limits");
        }
        pots.updateBalance(owner,pot.id(),balance);
    }
}
