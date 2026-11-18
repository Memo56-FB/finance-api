package dev.memofb.finance.recurring;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.*;

@Configuration(proxyBeanMethods=false)
@EnableScheduling
@ConditionalOnProperty(name="finance.recurring-payments.scheduling-enabled",havingValue="true",matchIfMissing=true)
class RecurringSchedulingConfiguration {
    @Bean
    ScheduledPayments scheduledPayments(RecurringProcessor processor) { return new ScheduledPayments(processor); }

    static class ScheduledPayments {
        private final RecurringProcessor processor;
        ScheduledPayments(RecurringProcessor processor) { this.processor=processor; }
        @Scheduled(fixedDelayString="${finance.recurring-payments.interval:PT60S}",initialDelayString="${finance.recurring-payments.interval:PT60S}")
        void run() { processor.processDue(); }
    }
}
