package dev.memofb.finance.summary;

import java.math.BigDecimal;
import java.time.LocalDate;

record FinancialSummaryResponse(LocalDate periodStart, LocalDate periodEnd, BigDecimal currentBalance,
        BigDecimal income, BigDecimal expenses) { }
