package dev.memofb.finance.recurring;

import java.time.*;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import dev.memofb.finance.shared.FinancialTestSupport;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class RecurringSubscriptionTest extends FinancialTestSupport {
    @Autowired RecurringProcessor processor;

    @ParameterizedTest @ValueSource(ints={1,2,3,4,5,6,7,8,9,10,11,12})
    void subscriptionIntervalsControlListingManualPaymentsAndAutomaticProcessing(int interval) throws Exception {
        long category=category("Bills");
        var created=create("/api/recurring-payments",subscription(category,"2027-03-31",interval));
        long id=created.get("id").asLong();
        assertThat(created.get("intervalMonths").asInt()).isEqualTo(interval);
        var nextMonth=YearMonth.of(2027,3).plusMonths(interval);
        var expectedDue=nextMonth.atDay(Math.min(31,nextMonth.lengthOfMonth()));
        var path="/api/recurring-payments/"+id;
        assertThat(read(path+"?month="+nextMonth).get("dueDate").asString()).isEqualTo(expectedDue.toString());
        assertThat(read("/api/recurring-payments?month="+nextMonth).get("totalElements").asLong()).isEqualTo(1);
        if(interval>1) {
            var skipped=YearMonth.of(2027,4);
            assertThat(read(path+"?month="+skipped).get("status").asString()).isEqualTo("NOT_SCHEDULED");
            assertThat(read("/api/recurring-payments?month="+skipped).get("totalElements").asLong()).isZero();
            var summary=read("/api/recurring-payments/summary?month="+skipped);
            assertThat(summary.get("pending").get("count").asLong()).isZero();
            assertThat(summary.get("total").get("totalAmount").decimalValue()).isZero();
            http(put(path+"/payments/"+skipped),400);
        }
        var paid=http(put(path+"/payments/"+nextMonth),200);
        var repeated=http(put(path+"/payments/"+nextMonth),200);
        assertThat(paid.get("transaction").get("id").asLong()).isEqualTo(repeated.get("transaction").get("id").asLong());
        assertThat(jdbc.queryForObject("SELECT next_auto_sequence FROM recurring_payments WHERE id=?",Integer.class,id)).isEqualTo(1);
        assertThat(processor.processDue()).isZero();
        when(clock.instant()).thenReturn(expectedDue.atTime(12,0).toInstant(ZoneOffset.UTC));
        assertThat(processor.processDue()).isEqualTo(2);
        assertThat(processor.processDue()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transactions",Long.class)).isEqualTo(2);
        assertThat(read("/api/recurring-payments/summary?month="+nextMonth).get("paid").get("count").asLong()).isEqualTo(1);
    }

    @Test
    void annualSubscriptionPreservesLeapDayAndOnlyChargesAnnualOccurrences() throws Exception {
        long category=category("Bills");
        var result=create("/api/recurring-payments",subscription(category,"2028-02-29",12));
        long id=result.get("id").asLong();
        when(clock.instant()).thenReturn(Instant.parse("2032-02-29T12:00:00Z"));
        assertThat(processor.processDue()).isEqualTo(5);
        assertThat(jdbc.queryForList("SELECT transaction_date::text FROM transactions ORDER BY id",String.class))
                .containsExactly("2028-02-29","2029-02-28","2030-02-28","2031-02-28","2032-02-29");
        assertThat(read("/api/recurring-payments/"+id+"?month=2029-02").get("dueDate").asString()).isEqualTo("2029-02-28");
        assertThat(read("/api/recurring-payments?month=2029-03").get("items").size()).isZero();
    }

    @ParameterizedTest @ValueSource(ints={-1,0,13})
    void rejectsOutOfRangeIntervals(int interval) throws Exception {
        long category=category("Bills");
        http(post("/api/recurring-payments").contentType("application/json").content(json.writeValueAsString(subscription(category,"2027-03-31",interval))),400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM recurring_payments",Long.class)).isZero();
    }

    @Test
    void omittedIntervalDefaultsToMonthlyAndInstallmentsRejectOtherIntervals() throws Exception {
        long category=category("Bills");
        var legacy=new HashMap<String,Object>(subscription(category,"2027-03-31",1));
        legacy.remove("intervalMonths");
        assertThat(create("/api/recurring-payments",legacy).get("intervalMonths").asInt()).isEqualTo(1);
        var installment=new HashMap<String,Object>(subscription(category,"2027-03-31",2));
        installment.put("type","INSTALLMENT");installment.put("totalInstallments",12);
        http(post("/api/recurring-payments").contentType("application/json").content(json.writeValueAsString(installment)),400);
        installment.put("intervalMonths",1);
        assertThat(create("/api/recurring-payments",installment).get("intervalMonths").asInt()).isEqualTo(1);
    }

    private Map<String,Object> subscription(long category,String first,int interval) {
        return Map.of("name","Subscription","categoryId",category,"amount",20,"firstDueDate",first,
                "type","SUBSCRIPTION","intervalMonths",interval);
    }
}
