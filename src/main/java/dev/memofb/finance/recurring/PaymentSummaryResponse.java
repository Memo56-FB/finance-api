package dev.memofb.finance.recurring;

import java.math.BigDecimal;
import java.time.YearMonth;

record PaymentSummaryResponse(YearMonth month, Total paid, Total pending, Total dueSoon, Total total) {
    record Total(long count, BigDecimal totalAmount) { }
}
