package dev.memofb.finance.budgets;

import java.math.BigDecimal;
import java.time.YearMonth;

public record BudgetWarning(long budgetId, long categoryId, YearMonth month, boolean limitReached, BigDecimal exceededAmount) { }
