package dev.memofb.finance.budgets;

import java.math.BigDecimal;
import java.time.YearMonth;

public record BudgetResponse(long id, long categoryId, String categoryName, BigDecimal spendingLimit,
        String color, YearMonth month, BigDecimal spent, BigDecimal available, boolean limitReached, BigDecimal exceededAmount) { }
