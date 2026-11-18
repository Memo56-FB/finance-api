package dev.memofb.finance.budgets;

import java.math.BigDecimal;
import java.util.Locale;
import jakarta.validation.constraints.*;

record BudgetRequest(@NotNull @Positive Long categoryId,
        @NotNull @DecimalMin("0.01") @DecimalMax("99999999999999999.99") @Digits(integer=17,fraction=2) BigDecimal spendingLimit,
        @NotNull @Pattern(regexp="#[0-9A-F]{6}") String color) {
    BudgetRequest { color=color==null?null:color.strip().toUpperCase(Locale.ROOT); }
}
