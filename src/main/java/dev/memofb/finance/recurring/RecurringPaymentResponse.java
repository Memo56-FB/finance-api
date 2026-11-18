package dev.memofb.finance.recurring;

import java.math.BigDecimal;
import java.time.*;

record RecurringPaymentResponse(long id, String name, long categoryId, String categoryName, PaymentType type,
        BigDecimal amount, LocalDate firstDueDate, int billingDay, Integer totalInstallments, int intervalMonths, long paidInstallments,
        YearMonth month, LocalDate dueDate, String status, Long occurrenceId, BigDecimal paidAmount, Instant paidAt) { }
