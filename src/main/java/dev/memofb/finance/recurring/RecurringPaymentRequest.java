package dev.memofb.finance.recurring;

import java.math.BigDecimal;
import java.time.LocalDate;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.*;

record RecurringPaymentRequest(@NotBlank @Size(max=100) String name, @NotNull @Positive Long categoryId,
        @NotNull PaymentType type, @NotNull @DecimalMin("0.01") @DecimalMax("99999999999999999.99")
        @Digits(integer=17,fraction=2) BigDecimal amount, @NotNull LocalDate firstDueDate, @Positive Integer totalInstallments) {
    RecurringPaymentRequest { name=name==null?null:name.strip(); }
    @AssertTrue(message="INSTALLMENT requires totalInstallments; SUBSCRIPTION must omit it") @JsonIgnore
    public boolean isValidInstallmentCount() {
        return type==null || (type==PaymentType.INSTALLMENT ? totalInstallments!=null : totalInstallments==null);
    }
}
