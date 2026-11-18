package dev.memofb.finance.transactions;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import dev.memofb.finance.budgets.BudgetWarning;

public record TransactionResponse(long id, String name, Long categoryId, String categoryName, LocalDate transactionDate,
        BigDecimal amount, String sourceType, Long potId, String potName, Long recurringPaymentId, Long occurrenceId,
        boolean openingBalance, List<BudgetWarning> budgetWarnings) {
    public TransactionResponse withWarnings(List<BudgetWarning> warnings) {
        return new TransactionResponse(id,name,categoryId,categoryName,transactionDate,amount,sourceType,potId,potName,
                recurringPaymentId,occurrenceId,openingBalance,warnings);
    }
}
