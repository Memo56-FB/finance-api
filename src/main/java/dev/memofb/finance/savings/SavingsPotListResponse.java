package dev.memofb.finance.savings;

import java.math.BigDecimal;
import java.util.List;

record SavingsPotListResponse(List<SavingsPotResponse> items, BigDecimal totalBalance) {
}
