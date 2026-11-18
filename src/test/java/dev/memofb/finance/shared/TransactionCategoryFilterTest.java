package dev.memofb.finance.shared;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class TransactionCategoryFilterTest extends FinancialTestSupport {
    @Test
    void filtersBeforeCountingAndPagingWithoutChangingUnfilteredRequests() throws Exception {
        long selected=category("Same"), another=category("Same");
        for(int i=0;i<7;i++) transaction("Match "+i,selected,"2027-03-20","-10");
        transaction("Excluded",another,"2027-03-20","-2");
        assertThat(read("/api/transactions").get("totalElements").asLong()).isEqualTo(8);
        var result=read("/api/transactions?categoryId="+selected+"&search=Match&page=1&size=4&sort=newest");
        assertThat(result.get("totalElements").asLong()).isEqualTo(7);
        assertThat(result.get("totalPages").asLong()).isEqualTo(2);
        assertThat(result.get("items").size()).isEqualTo(3);
        assertThat(result.get("items").get(0).get("name").asString()).isEqualTo("Match 2");
        for(var item:result.get("items")) assertThat(item.get("categoryId").asLong()).isEqualTo(selected);
        http(delete("/api/categories/"+selected),204);
        assertThat(read("/api/transactions?categoryId="+selected).get("totalElements").asLong()).isEqualTo(7);
        assertThat(httpAs(OTHER,get("/api/transactions?categoryId="+selected),200).get("totalElements").asLong()).isZero();
        assertThat(read("/api/transactions?categoryId=9223372036854775807").get("items").size()).isZero();
    }

    @Test
    void combinesLiteralSearchAndStableSorts() throws Exception {
        long selected=category("Selected"), another=category("Other");
        transaction("A %_!",selected,"2027-03-20","-10");
        transaction("B %_!",selected,"2027-03-20","10");
        transaction("C wildcard",selected,"2027-03-20","-5");
        transaction("D %_!",another,"2027-03-20","-20");
        for(var sort:List.of("newest","oldest","name_asc","name_desc","amount_asc","amount_desc")) {
            var result=http(get("/api/transactions").param("categoryId",Long.toString(selected)).param("search","%_!").param("sort",sort),200);
            assertThat(result.get("totalElements").asLong()).isEqualTo(2);
            var first=result.get("items").get(0).get("name").asString();
            assertThat(first).isEqualTo(List.of("oldest","name_asc","amount_asc").contains(sort)?"A %_!":"B %_!");
        }
    }

    @ParameterizedTest @ValueSource(strings={"0","-1","abc","9223372036854775808"})
    void rejectsInvalidCategoryIds(String category) throws Exception {
        http(get("/api/transactions").param("categoryId",category),400);
    }
}
