package dev.memofb.finance.recurring;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import dev.memofb.finance.shared.FinancialTestSupport;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class RecurringPaymentTest extends FinancialTestSupport {
    @Autowired RecurringService service;
    @Autowired RecurringProcessor processor;

    @Test
    void manualPaymentsAreIdempotentAndProgressCountsActualPayments() throws Exception {
        long category=category("Bills"),id=bill("Laptop",category,"2027-01-05","50",12);
        create("/api/budgets",Map.of("categoryId",category,"spendingLimit",100,"color","#FFFFFF"));
        var path="/api/recurring-payments/"+id;
        long lastTransaction=0;
        for(var month:List.of("2027-01","2027-02","2027-03")) {
            var paid=http(put(path+"/payments/"+month),200);
            var repeated=http(put(path+"/payments/"+month),200);
            assertThat(paid.get("transaction").get("id").asLong()).isEqualTo(repeated.get("transaction").get("id").asLong());
            lastTransaction=paid.get("transaction").get("id").asLong();
            assertThat(paid.get("transaction").get("sourceType").asString()).isEqualTo("RECURRING_PAYMENT");
            assertThat(paid.get("transaction").get("transactionDate").asString()).isEqualTo("2027-03-20");
        }
        var detail=read(path);
        assertThat(detail.get("paidInstallments").asLong()).isEqualTo(3);
        assertThat(detail.get("totalInstallments").asInt()).isEqualTo(12);
        assertThat(detail.get("name").asString()).isEqualTo("Laptop");
        assertThat(read("/api/financial-summary").get("currentBalance").decimalValue()).isEqualByComparingTo("-150");
        assertThat(read("/api/recurring-payments/summary?month=2027-01").get("paid").get("totalAmount").decimalValue()).isEqualByComparingTo("50");
        var early=http(put(path+"/payments/2027-04"),200);
        assertThat(early.get("budgetWarnings").get(0).get("exceededAmount").decimalValue()).isEqualByComparingTo("100");
        assertThat(processor.processDue()).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transactions",Long.class)).isEqualTo(4);
        when(clock.instant()).thenReturn(Instant.parse("2027-04-05T12:00:00Z"));
        processor.processDue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transactions",Long.class)).isEqualTo(4);
        http(put(path+"/payments/2026-12"),400);http(put(path+"/payments/2028-01"),400);
    }

    @Test
    void automaticRecoveryClampsDatesAndStopsAfterFinalInstallment() throws Exception {
        long category=category("Bills"),id=bill("Three payments",category,"2027-01-31","30",3);
        read("/api/recurring-payments");read("/api/recurring-payments/summary");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transactions",Long.class)).isZero();
        assertThat(processor.processDue()).isEqualTo(2);
        assertThat(processor.processDue()).isZero();
        assertThat(jdbc.queryForList("SELECT transaction_date::text FROM transactions ORDER BY id",String.class))
                .containsExactly("2027-01-31","2027-02-28");
        assertThat(read("/api/financial-summary").get("expenses").decimalValue()).isZero();
        assertThat(read("/api/recurring-payments/summary?month=2027-02").get("paid").get("count").asLong()).isEqualTo(1);
        when(clock.instant()).thenReturn(Instant.parse("2027-03-31T12:00:00Z"));
        assertThat(processor.processDue()).isEqualTo(1);
        assertThat(read("/api/recurring-payments/"+id).get("paidInstallments").asLong()).isEqualTo(3);
        when(clock.instant()).thenReturn(Instant.parse("2027-04-30T12:00:00Z"));
        assertThat(processor.processDue()).isZero();
        assertThat(read("/api/recurring-payments").get("items").size()).isZero();
        assertThat(read("/api/recurring-payments/"+id).get("status").asString()).isEqualTo("NOT_SCHEDULED");
    }

    @Test
    void correctionsSuppressAutomaticRecreationAndRespectReplacementExpenses() throws Exception {
        long category=category("Bills"),other=category("Other"),id=bill("Bill",category,"2027-03-05","50",null);
        var paid=http(put("/api/recurring-payments/"+id+"/payments/2027-03"),200);
        long original=paid.get("transaction").get("id").asLong();
        write("/api/transactions/"+original,movement("Refund",other,"2027-03-19","45"));
        assertThat(read("/api/recurring-payments/"+id).get("paidInstallments").asLong()).isZero();
        processor.processDue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transactions",Long.class)).isEqualTo(1);
        var replacement=http(put("/api/recurring-payments/"+id+"/payments/2027-03"),200);
        long expense=replacement.get("transaction").get("id").asLong();
        write("/api/transactions/"+original,movement("Still income",other,"2027-03-19","40"));
        assertThat(read("/api/recurring-payments/"+id).get("paidInstallments").asLong()).isEqualTo(1);
        http(put("/api/transactions/"+original).contentType("application/json").content(json.writeValueAsString(movement("Conflict",other,"2027-03-19","-40"))),409);
        write("/api/transactions/"+expense,movement("Corrected bill",other,"2027-02-10","-35"));
        assertThat(read("/api/recurring-payments/summary").get("paid").get("totalAmount").decimalValue()).isEqualByComparingTo("35");
        assertThat(read("/api/recurring-payments/"+id).get("amount").decimalValue()).isEqualByComparingTo("50");
        http(delete("/api/transactions/"+expense),204);
        assertThat(processor.processDue()).isZero();
        assertThat(read("/api/recurring-payments/summary").get("pending").get("count").asLong()).isEqualTo(1);
        write("/api/transactions/"+original,movement("Expense again",other,"2027-03-19","-40"));
        assertThat(read("/api/recurring-payments/"+id).get("paidInstallments").asLong()).isEqualTo(1);
    }

    @Test
    void editingAndCancelingDefinitionsPreservesHistoryAndOwnership() throws Exception {
        long category=category("Bills"),other=category("Other"),id=bill("Original",category,"2027-03-05","10",2);
        var path="/api/recurring-payments/"+id;
        http(put(path+"/payments/2027-03"),200);
        var edited=Map.of("name","Edited","categoryId",other,"type","INSTALLMENT","amount",50,"firstDueDate","2027-03-25","totalInstallments",2);
        var updated=write(path,edited);
        assertThat(updated.get("dueDate").asString()).isEqualTo("2027-03-05");
        assertThat(updated.get("paidAmount").decimalValue()).isEqualByComparingTo("10");
        var invalid=new HashMap<String,Object>(edited);invalid.put("firstDueDate","2027-04-25");
        http(put(path).contentType("application/json").content(json.writeValueAsString(invalid)),409);
        when(clock.instant()).thenReturn(Instant.parse("2027-04-25T12:00:00Z"));
        assertThat(processor.processDue()).isEqualTo(2);
        assertThat(read(path).get("paidAmount").decimalValue()).isEqualByComparingTo("50");
        invalid=new HashMap<>(edited);invalid.put("totalInstallments",1);
        http(put(path).contentType("application/json").content(json.writeValueAsString(invalid)),409);
        httpAs(OTHER,get(path),404);httpAs(OTHER,put(path+"/payments/2027-04"),404);
        httpAs(OTHER,delete(path),404);httpAs(OTHER,put(path).contentType("application/json").content(json.writeValueAsString(edited)),404);
        http(delete(path),204);
        assertThat(read("/api/recurring-payments").get("items").size()).isZero();
        assertThat(read("/api/recurring-payments/summary?month=2027-03").get("paid").get("totalAmount").decimalValue()).isEqualByComparingTo("10");
        assertThat(processor.processDue()).isZero();
    }

    @Test
    void monthlySummaryMatchesRequestedExampleAndDoesNotDoubleCountDueSoon() throws Exception {
        long category=category("Bills");
        for(int i=0;i<5;i++) {
            long id=bill("Paid "+i,category,"2027-03-05","31",null);
            http(put("/api/recurring-payments/"+id+"/payments/2027-03"),200);
        }
        bill("Soon A",category,"2027-03-22","20",null);
        bill("Soon B",category,"2027-03-27","20",null);
        bill("Later",category,"2027-03-28","260",null);
        var summary=read("/api/recurring-payments/summary");
        assertThat(summary.get("paid").get("count").asLong()).isEqualTo(5);
        assertThat(summary.get("paid").get("totalAmount").decimalValue()).isEqualByComparingTo("155");
        assertThat(summary.get("pending").get("count").asLong()).isEqualTo(3);
        assertThat(summary.get("pending").get("totalAmount").decimalValue()).isEqualByComparingTo("300");
        assertThat(summary.get("dueSoon").get("count").asLong()).isEqualTo(2);
        assertThat(summary.get("dueSoon").get("totalAmount").decimalValue()).isEqualByComparingTo("40");
        assertThat(summary.get("total").get("count").asLong()).isEqualTo(8);
        assertThat(summary.get("total").get("totalAmount").decimalValue()).isEqualByComparingTo("455");
        write("/api/financial-summary/settings",Map.of("frequency","SEMIMONTHLY","startDay",4));
        read("/api/recurring-payments?search=Soon&size=1");
        assertThat(read("/api/recurring-payments/summary")).isEqualTo(summary);
    }

    @ParameterizedTest @ValueSource(strings={"newest","oldest","name_asc","name_desc","amount_desc","amount_asc"})
    void recurringSearchAndAllSortsAreIndependent(String sort) throws Exception {
        long category=category("Bills"),a=bill("Match A",category,"2027-03-01","30",null),b=bill("MATCH B",category,"2027-03-02","10",null);
        bill("Other",category,"2027-03-03","5",null);
        var list=read("/api/recurring-payments?search=match&sort="+sort);
        long expected=Set.of("newest","name_desc","amount_asc").contains(sort)?b:a;
        assertThat(list.get("items").get(0).get("id").asLong()).isEqualTo(expected);
        assertThat(list.get("totalElements").asLong()).isEqualTo(2);
        assertThat(read("/api/recurring-payments?sort="+sort).get("totalElements").asLong()).isEqualTo(3);
        bill("100%_!",category,"2027-03-03","5",null);
        assertThat(http(get("/api/recurring-payments").param("search","%_!"),200).get("items").size()).isEqualTo(1);
        for(var query:List.of("size=101","page=-1","sort=bad","month=2027-99")) http(get("/api/recurring-payments?"+query),400);
    }

    @Test
    void invalidDefinitionsAndForeignCategoriesAreRejected() throws Exception {
        long category=category("Bills");
        var good=new HashMap<String,Object>(Map.of("name","Bill","categoryId",category,"type","INSTALLMENT","amount",10,"firstDueDate","2027-03-01","totalInstallments",12));
        for(var replacement:List.of(Map.of("amount",0),Map.of("amount",new BigDecimal("0.001")),Map.of("totalInstallments",0),Map.of("type","BAD"),Map.of("type","SUBSCRIPTION"),Map.of("name"," "),Map.of("firstDueDate","2027-02-30"))) {
            var invalid=new HashMap<String,Object>(good);invalid.putAll(replacement);
            http(post("/api/recurring-payments").contentType("application/json").content(json.writeValueAsString(invalid)),400);
        }
        good.remove("totalInstallments");
        http(post("/api/recurring-payments").contentType("application/json").content(json.writeValueAsString(good)),400);
        good.put("totalInstallments",12);
        httpAs(OTHER,post("/api/recurring-payments").contentType("application/json").content(json.writeValueAsString(good)),404);
    }

    @Test
    void paymentFailureRollsBackOccurrenceAndOtherSeriesContinue() throws Exception {
        long category=category("Bills"),bad=bill("Fail once",category,"2027-03-01","42",null),good=bill("Good",category,"2027-03-01","3",null);
        jdbc.execute("ALTER TABLE transactions ADD CONSTRAINT reject_amount_test CHECK(amount<>-42)");
        try {
            http(put("/api/recurring-payments/"+bad+"/payments/2027-03"),409);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM recurring_payment_occurrences",Long.class)).isZero();
            assertThat(processor.processDue()).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT next_auto_sequence FROM recurring_payments WHERE id=?",Integer.class,bad)).isEqualTo(1);
            assertThat(read("/api/recurring-payments/"+good).get("paidInstallments").asLong()).isEqualTo(1);
        } finally { jdbc.execute("ALTER TABLE transactions DROP CONSTRAINT reject_amount_test"); }
        assertThat(processor.processDue()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transactions",Long.class)).isEqualTo(2);
    }

    @Test
    void twoProcessorsAndManualPaymentRecordOneExpense() throws Exception {
        long category=category("Bills"),id=bill("Race",category,"2027-03-05","50",null);
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(3)) {
            var tasks=List.<Callable<Object>>of(()->{start.await();return processor.processDue();},()->{start.await();return processor.processDue();},
                    ()->{start.await();return service.pay(OWNER,id,YearMonth.of(2027,3));});
            var futures=tasks.stream().map(pool::submit).toList();start.countDown();
            for(var result:futures) result.get(10,TimeUnit.SECONDS);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transactions WHERE amount<0 AND deleted_at IS NULL",Long.class)).isEqualTo(1);
        assertThat(read("/api/financial-summary").get("expenses").decimalValue()).isEqualByComparingTo("50");
        assertThat(read("/api/recurring-payments/"+id).get("paidInstallments").asLong()).isEqualTo(1);
    }

    @Test
    void concurrentCorrectionCancellationAndProcessingDoNotRecreateExpense() throws Exception {
        long category=category("Bills"),id=bill("Race",category,"2027-03-05","50",null);
        long tx=http(put("/api/recurring-payments/"+id+"/payments/2027-03"),200).get("transaction").get("id").asLong();
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(3)) {
            var tasks=List.<Callable<Object>>of(()->{start.await();return processor.processDue();},
                    ()->{start.await();return write("/api/transactions/"+tx,movement("Refund",category,"2027-03-20","5"));},
                    ()->{start.await();service.delete(OWNER,id);return true;});
            var futures=tasks.stream().map(pool::submit).toList();start.countDown();for(var future:futures)future.get(10,TimeUnit.SECONDS);
        }
        assertThat(processor.processDue()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transactions",Long.class)).isEqualTo(1);
        assertThat(read("/api/financial-summary").get("expenses").decimalValue()).isZero();
    }

    @Test
    void boundedBatchesEventuallyReachDefinitionsBeyondFirstPage() throws Exception {
        long category=category("Bills");
        jdbc.update("""
                INSERT INTO recurring_payments(username,name,category_id,type,amount,first_due_date)
                SELECT ?, 'Bill '||n, ?, 'SUBSCRIPTION',1,'2027-03-01'::date FROM generate_series(1,105) n
                """,OWNER,category);
        assertThat(processor.processDue()).isEqualTo(100);
        assertThat(processor.processDue()).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transactions",Long.class)).isEqualTo(105);
    }
}
