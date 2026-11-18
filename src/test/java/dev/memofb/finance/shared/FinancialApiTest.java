package dev.memofb.finance.shared;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.awaitility.Awaitility.await;

class FinancialApiTest extends FinancialTestSupport {
    @Autowired FinancialLock ownerLock;
    @Autowired PlatformTransactionManager transactionManager;

    @ParameterizedTest @ValueSource(strings={"", " ", "\t"})
    void rejectsBlankNames(String name) throws Exception {
        http(post("/api/categories").contentType("application/json").content(json.writeValueAsString(Map.of("name",name))),400);
        assertThat(read("/api/categories").get("items").size()).isZero();
    }

    @Test
    void summaryCutsBoundDatesAndChangeWithoutResettingRecords() throws Exception {
        long category=category("General");
        assertThat(read("/api/financial-summary/settings").get("frequency").asString()).isEqualTo("MONTHLY");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_summary_preferences",Long.class)).isZero();
        transaction("Earlier",category,"2027-03-03","500");
        transaction("First boundary",category,"2027-03-04","100");
        transaction("Last boundary",category,"2027-04-03","-20");
        transaction("After",category,"2027-04-04","200");
        write("/api/financial-summary/settings",Map.of("frequency","MONTHLY","startDay",4));
        var totals=read("/api/financial-summary");
        assertThat(totals.get("periodStart").asString()).isEqualTo("2027-03-04");
        assertThat(totals.get("periodEnd").asString()).isEqualTo("2027-04-03");
        assertThat(totals.get("currentBalance").decimalValue()).isEqualByComparingTo("80");
        write("/api/financial-summary/settings",Map.of("frequency","SEMIMONTHLY","startDay",4));
        totals=read("/api/financial-summary");
        assertThat(totals.get("periodStart").asString()).isEqualTo("2027-03-19");
        assertThat(totals.get("expenses").decimalValue()).isEqualByComparingTo("20");
        assertThat(totals.get("currentBalance").decimalValue()).isEqualByComparingTo("-20");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transactions",Long.class)).isEqualTo(4);
        assertThat(httpAs(OTHER,get("/api/financial-summary/settings"),200).get("startDay").asInt()).isEqualTo(1);
        when(clock.instant()).thenReturn(Instant.parse("2027-04-04T12:00:00Z"));
        assertThat(read("/api/financial-summary").get("currentBalance").decimalValue()).isEqualByComparingTo("200");
        when(clock.instant()).thenReturn(Instant.parse("2027-05-04T12:00:00Z"));
        assertThat(read("/api/financial-summary").get("currentBalance").decimalValue()).isZero();
    }

    @ParameterizedTest @ValueSource(strings={"{\"frequency\":\"MONTHLY\",\"startDay\":0}","{\"frequency\":\"MONTHLY\",\"startDay\":32}","{\"frequency\":\"WEEKLY\",\"startDay\":4}","{}"})
    void invalidPreferencesLeavePreviousConfiguration(String body) throws Exception {
        write("/api/financial-summary/settings",Map.of("frequency","SEMIMONTHLY","startDay",4));
        http(put("/api/financial-summary/settings").contentType("application/json").content(body),400);
        assertThat(read("/api/financial-summary/settings").get("startDay").asInt()).isEqualTo(4);
    }

    @ParameterizedTest @ValueSource(strings={"0","0.001","100000000000000000","-100000000000000000"})
    void rejectsInvalidMoneyWithoutRounding(String amount) throws Exception {
        long category=category("General");
        http(post("/api/transactions").contentType("application/json").content(json.writeValueAsString(movement("Invalid",category,"2027-03-20",amount))),400);
        assertThat(read("/api/transactions").get("totalElements").asLong()).isZero();
    }

    @Test
    void manualCorrectionsAndPaginationDoNotLimitAggregates() throws Exception {
        long category=category("General"), another=category("Other");
        var first=transaction("First",category,"2027-03-20","-10.25");
        for(int i=0;i<25;i++) transaction("Salary "+i,category,"2027-03-20","1.00");
        assertThat(read("/api/transactions").get("items").size()).isEqualTo(20);
        assertThat(read("/api/financial-summary").get("currentBalance").decimalValue()).isEqualByComparingTo("14.75");
        assertThat(read("/api/transactions?page=1").get("items").size()).isEqualTo(6);
        assertThat(read("/api/transactions?page=200").get("items").size()).isZero();
        long id=first.get("id").asLong();
        write("/api/transactions/"+id,movement("Corrected",another,"2027-02-01","50"));
        assertThat(read("/api/financial-summary").get("currentBalance").decimalValue()).isEqualByComparingTo("25");
        httpAs(OTHER,get("/api/transactions/"+id),404);
        httpAs(OTHER,delete("/api/transactions/"+id),404);
        http(delete("/api/transactions/"+id),204);
        http(get("/api/transactions/"+id),404);
        httpAs(OTHER,post("/api/transactions").contentType("application/json").content(json.writeValueAsString(movement("Foreign",category,"2027-03-20","1"))),404);
        for(var query:List.of("size=101","page=-1","sort=bad","sort=name%3BDROP%20TABLE%20users")) http(get("/api/transactions?"+query),400);
    }

    @ParameterizedTest @ValueSource(strings={"newest","oldest","name_asc","name_desc","amount_desc","amount_asc"})
    void searchAndSortAreIndependentWithLiteralWildcards(String sort) throws Exception {
        long category=category("General");
        var a=transaction("match A",category,"2027-03-01","-20");
        var b=transaction("MATCH B",category,"2027-03-02","10");
        var c=transaction("other",category,"2027-03-03","100");
        var matching=read("/api/transactions?search=match&sort="+sort).get("items");
        long expected=Set.of("newest","name_desc","amount_desc").contains(sort)?b.get("id").asLong():a.get("id").asLong();
        assertThat(matching.size()).isEqualTo(2);assertThat(matching.get(0).get("id").asLong()).isEqualTo(expected);
        assertThat(read("/api/transactions?sort="+sort).get("totalElements").asLong()).isEqualTo(3);
        transaction("100%_! match",category,"2027-03-04","1");
        assertThat(http(get("/api/transactions").param("search","%_!"),200).get("items").size()).isEqualTo(1);
    }

    @Test
    void monthlyBudgetsWarnWithoutBlockingAndRecomputeAfterCorrections() throws Exception {
        long category=category("Food"), otherCategory=category("Other");
        var budget=create("/api/budgets",Map.of("categoryId",category,"spendingLimit",100,"color","#aabbcc"));
        assertThat(budget.get("color").asString()).isEqualTo("#AABBCC");
        http(post("/api/budgets").contentType("application/json").content(json.writeValueAsString(Map.of("categoryId",category,"spendingLimit",100,"color","#FFFFFF"))),409);
        transaction("Income",category,"2027-03-20","1000");
        transaction("Previous",category,"2027-02-28","-500");
        transaction("Food",category,"2027-03-20","-80");
        var atLimit=transaction("Limit",category,"2027-03-20","-20");
        assertThat(atLimit.get("budgetWarnings").get(0).get("exceededAmount").decimalValue()).isZero();
        var excess=transaction("Extra",category,"2027-03-20","-10");
        assertThat(excess.get("budgetWarnings").get(0).get("exceededAmount").decimalValue()).isEqualByComparingTo("10");
        var path="/api/budgets/"+budget.get("id").asLong();
        assertThat(read(path).get("available").decimalValue()).isEqualByComparingTo("-10");
        write("/api/financial-summary/settings",Map.of("frequency","SEMIMONTHLY","startDay",4));
        assertThat(read(path).get("spent").decimalValue()).isEqualByComparingTo("110");
        write("/api/transactions/"+excess.get("id").asLong(),movement("Moved",otherCategory,"2027-04-01","-10"));
        assertThat(read(path).get("spent").decimalValue()).isEqualByComparingTo("100");
        http(delete("/api/transactions/"+atLimit.get("id").asLong()),204);
        assertThat(read(path).get("available").decimalValue()).isEqualByComparingTo("20");
        assertThat(read(path+"?month=2027-02").get("spent").decimalValue()).isEqualByComparingTo("500");
        httpAs(OTHER,get(path),404);http(get(path+"?month=2027-99"),400);
        httpAs(OTHER,post("/api/budgets").contentType("application/json").content(json.writeValueAsString(Map.of("categoryId",category,"spendingLimit",100,"color","#FFFFFF"))),404);
    }

    @Test
    void ownerLocksDoNotBlockAnotherUser() throws Exception {
        var tx=new TransactionTemplate(transactionManager);
        try(var workers=Executors.newFixedThreadPool(2)) {
            var held=new CountDownLatch(1);var release=new CountDownLatch(1);
            var first=workers.submit(()->tx.execute(s->{ownerLock.acquire(OWNER);held.countDown();try { release.await(10,TimeUnit.SECONDS); }catch(InterruptedException e){throw new RuntimeException(e);}return true;}));
            try {
                assertThat(held.await(5,TimeUnit.SECONDS)).isTrue();
                assertThat(workers.submit(()->tx.execute(s->{ownerLock.acquire(OTHER);return true;})).get(5,TimeUnit.SECONDS)).isTrue();
            } finally { release.countDown(); }
            assertThat(first.get(5,TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void summaryUsesOnePreferenceSnapshotDuringConcurrentUpdate() throws Exception {
        long category=category("General");
        transaction("Income",category,"2027-03-04","100");
        var tx=new TransactionTemplate(transactionManager);
        try(var workers=Executors.newSingleThreadExecutor()) {
            var pending=tx.execute(s->{
                jdbc.execute("LOCK TABLE transactions IN ACCESS EXCLUSIVE MODE");
                var result=workers.submit(()->read("/api/financial-summary"));
                await().atMost(Duration.ofSeconds(5)).until(()->jdbc.queryForObject("""
                        SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE wait_event_type='Lock'
                            AND query LIKE '%FROM effective_transactions WHERE username=%')
                        """,Boolean.class));
                try { write("/api/financial-summary/settings",Map.of("frequency","SEMIMONTHLY","startDay",4)); }
                catch(Exception failure) { throw new RuntimeException(failure); }
                return result;
            });
            var snapshot=pending.get(10,TimeUnit.SECONDS);
            assertThat(snapshot.get("periodStart").asString()).isEqualTo("2027-03-01");
            assertThat(snapshot.get("income").decimalValue()).isEqualByComparingTo("100");
            var next=read("/api/financial-summary");
            assertThat(next.get("periodStart").asString()).isEqualTo("2027-03-19");
            assertThat(next.get("income").decimalValue()).isZero();
        }
    }

    @Test
    void budgetEditsValidateAmountsColorsAndLeaveTotalsOnDeletion() throws Exception {
        long category=category("Food"),other=category("Other");
        create("/api/budgets",Map.of("categoryId",category,"spendingLimit",100,"color","#FFFFFF"));
        transaction("Expense",other,"2027-03-20","-30");
        var result=write("/api/budgets/1",Map.of("categoryId",other,"spendingLimit",20,"color","#aabbcc"));
        assertThat(result.get("exceededAmount").decimalValue()).isEqualByComparingTo("10");
        for(var patch:List.of(Map.of("color","red"),Map.of("spendingLimit",0),Map.of("spendingLimit",new BigDecimal("0.001")))) {
            var invalid=new HashMap<String,Object>(Map.of("categoryId",other,"spendingLimit",20,"color","#FFFFFF"));invalid.putAll(patch);
            http(put("/api/budgets/1").contentType("application/json").content(json.writeValueAsString(invalid)),400);
        }
        transaction("Leap day",other,"2028-02-29","-7");
        transaction("Next month",other,"2028-03-01","-9");
        assertThat(read("/api/budgets/1?month=2028-02").get("spent").decimalValue()).isEqualByComparingTo("7");
        var before=read("/api/financial-summary");http(delete("/api/budgets/1"),204);
        assertThat(read("/api/financial-summary")).isEqualTo(before);
    }


}
