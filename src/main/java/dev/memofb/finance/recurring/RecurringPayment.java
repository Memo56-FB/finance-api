package dev.memofb.finance.recurring;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import dev.memofb.finance.shared.FinancialException;

record RecurringPayment(long id, String name, long categoryId, PaymentType type, BigDecimal amount,
        LocalDate firstDueDate, Integer totalInstallments, int nextAutoSequence, int intervalMonths) {
    int sequence(YearMonth month) {
        long elapsed=ChronoUnit.MONTHS.between(YearMonth.from(firstDueDate),month);
        long value=elapsed/intervalMonths+1;
        if(elapsed<0 || elapsed%intervalMonths!=0 || value>Integer.MAX_VALUE || (totalInstallments!=null && value>totalInstallments)) {
            throw FinancialException.badRequest("The requested month is outside the payment schedule");
        }
        return (int)value;
    }
    LocalDate dueDate(int sequence) {
        var month=YearMonth.from(firstDueDate).plusMonths((sequence-1L)*intervalMonths);
        return month.atDay(Math.min(firstDueDate.getDayOfMonth(),month.lengthOfMonth()));
    }
}
