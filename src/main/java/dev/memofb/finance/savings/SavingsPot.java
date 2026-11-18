package dev.memofb.finance.savings;

import java.math.BigDecimal;
import java.time.Instant;

public record SavingsPot(long id, String name, BigDecimal targetAmount, BigDecimal balance, String color,
        Instant deletedAt) {
}
