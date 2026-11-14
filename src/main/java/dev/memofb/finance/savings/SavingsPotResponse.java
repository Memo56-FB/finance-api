package dev.memofb.finance.savings;

import java.math.BigDecimal;

record SavingsPotResponse(long id, String name, BigDecimal targetAmount, BigDecimal balance, String color) {

    static SavingsPotResponse from(SavingsPot pot) {
        return new SavingsPotResponse(pot.id(), pot.name(), pot.targetAmount(), pot.balance(), pot.color());
    }
}
