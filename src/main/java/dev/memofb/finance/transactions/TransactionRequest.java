package dev.memofb.finance.transactions;

import java.math.BigDecimal;
import java.time.LocalDate;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.*;

record TransactionRequest(@NotBlank @Size(max=100) String name, @Positive Long categoryId,
        @NotNull LocalDate transactionDate,
        @NotNull @DecimalMin("-99999999999999999.99") @DecimalMax("99999999999999999.99") @Digits(integer=17,fraction=2) BigDecimal amount) {
    TransactionRequest { name=name==null?null:name.strip(); }
    @AssertTrue(message="amount must be nonzero") @JsonIgnore
    public boolean isNonzeroAmount() { return amount==null || amount.signum()!=0; }
}
