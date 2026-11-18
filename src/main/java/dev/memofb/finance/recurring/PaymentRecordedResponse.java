package dev.memofb.finance.recurring;

import java.time.*;
import java.util.List;
import dev.memofb.finance.transactions.TransactionResponse;
import dev.memofb.finance.budgets.BudgetWarning;

record PaymentRecordedResponse(long occurrenceId, long recurringPaymentId, YearMonth month, String status, Instant paidAt,
        TransactionResponse transaction, List<BudgetWarning> budgetWarnings) { }
