package dev.memofb.finance.summary;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import dev.memofb.finance.shared.*;

@Service
class SummaryService {
    private final SummaryRepository repository;
    private final FinancialLock lock;
    private final BusinessCalendar calendar;
    SummaryService(SummaryRepository repository, FinancialLock lock, BusinessCalendar calendar) {
        this.repository=repository; this.lock=lock; this.calendar=calendar;
    }
    public SummarySettings settings(String owner) { return repository.settings(owner); }
    @Transactional
    public SummarySettings save(String owner, SummarySettings settings) {
        lock.acquire(owner); repository.save(owner,settings); return settings;
    }
    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public FinancialSummaryResponse summary(String owner, String month) {
        if (month != null) {
            var selected = BusinessCalendar.parseMonth(month);
            return repository.summary(owner, new ReportingPeriod(selected.atDay(1), selected.plusMonths(1).atDay(1)));
        }
        var today=calendar.today();
        return repository.summary(owner, ReportingPeriod.containing(today, repository.settings(owner)));
    }
}
