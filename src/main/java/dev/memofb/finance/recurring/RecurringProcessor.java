package dev.memofb.finance.recurring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import dev.memofb.finance.shared.BusinessCalendar;

@Component
public class RecurringProcessor {
    private static final Logger log=LoggerFactory.getLogger(RecurringProcessor.class);
    private final RecurringRepository payments;
    private final RecurringService service;
    private final BusinessCalendar calendar;
    RecurringProcessor(RecurringRepository payments,RecurringService service,BusinessCalendar calendar) {
        this.payments=payments;this.service=service;this.calendar=calendar;
    }
    public int processDue() {
        int processed=0;long afterId=0;
        while(processed<100) {
            var candidates=payments.candidates(afterId,calendar.today());
            if(candidates.isEmpty()) break;
            for(var candidate:candidates) {
                afterId=candidate.id();
                try {
                    while(processed<100 && service.processNext(candidate.owner(),candidate.id())) processed++;
                } catch(RuntimeException failure) {
                    log.error("Recurring payment {} could not be processed ({})",candidate.id(),failure.getClass().getSimpleName());
                }
                if(processed==100) break;
            }
        }
        return processed;
    }
}
