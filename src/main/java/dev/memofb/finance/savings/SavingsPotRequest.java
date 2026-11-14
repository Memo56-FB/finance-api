package dev.memofb.finance.savings;

import java.math.BigDecimal;
import java.util.Locale;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

record SavingsPotRequest(
        @NotBlank @Size(max = 100) String name,
        @NotNull @DecimalMin("0.01") @DecimalMax("99999999999999999.99")
        @Digits(integer = 17, fraction = 2) BigDecimal targetAmount,
        @NotNull @Pattern(regexp = "#[0-9A-F]{6}") String color) {

    SavingsPotRequest {
        name = name == null ? null : name.strip();
        color = color == null ? null : color.strip().toUpperCase(Locale.ROOT);
    }
}
