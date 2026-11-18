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


}
