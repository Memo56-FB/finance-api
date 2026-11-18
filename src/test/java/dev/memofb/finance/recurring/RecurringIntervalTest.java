package dev.memofb.finance.recurring;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class RecurringIntervalTest {
    @ParameterizedTest
    @ValueSource(ints={1,2,3,4,5,6,7,8,9,10,11,12})
    void mapsDueMonthsToStableSequences(int interval) {
        var payment=subscription(LocalDate.of(2027,1,31),interval);
        var start=YearMonth.of(2027,1);
        for(int sequence=1;sequence<=5;sequence++) {
            var month=start.plusMonths((sequence-1L)*interval);
            assertThat(payment.sequence(month)).isEqualTo(sequence);
            assertThat(YearMonth.from(payment.dueDate(sequence))).isEqualTo(month);
            assertThat(payment.dueDate(sequence).getDayOfMonth()).isEqualTo(month.lengthOfMonth());
        }
        assertThatThrownBy(()->payment.sequence(start.minusMonths(1))).hasMessageContaining("outside");
        if(interval>1) assertThatThrownBy(()->payment.sequence(start.plusMonths(1))).hasMessageContaining("outside");
    }

    @Test
    void annualSubscriptionsReturnToTheOriginalLeapDayAnchor() {
        var payment=subscription(LocalDate.of(2028,2,29),12);
        assertThat(payment.dueDate(2)).isEqualTo(LocalDate.of(2029,2,28));
        assertThat(payment.dueDate(5)).isEqualTo(LocalDate.of(2032,2,29));
    }

    private RecurringPayment subscription(LocalDate first,int interval) {
        return new RecurringPayment(1,"Subscription",1,PaymentType.SUBSCRIPTION,BigDecimal.TEN,first,null,1,interval);
    }
}
