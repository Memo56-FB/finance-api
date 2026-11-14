package dev.memofb.finance.savings;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

record MoneyAmountRequest(
        @NotNull @DecimalMin("0.01") @DecimalMax("99999999999999999.99")
        @Digits(integer = 17, fraction = 2) BigDecimal amount) {
}
