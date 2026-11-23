package dev.memofb.finance.shared;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

class OverviewHistoryTest extends FinancialTestSupport {
    @Test
    void selectedCalendarMonthIsPrivateAndDoesNotChangeTheCurrentCutOrOtherLists() throws Exception {
        var category = category("Salary");
        transaction("Outside", category, "2024-01-31", "1000");
        transaction("First", category, "2024-02-01", "-20");
        transaction("Last", category, "2024-02-29", "120");
        transaction("Outside", category, "2024-03-01", "400");
        transaction("Current", category, "2027-03-20", "8");
        write("/api/financial-summary/settings", Map.of("frequency", "SEMIMONTHLY", "startDay", 4));

        var session = new MockHttpSession();
        var selected = http(get("/api/financial-summary").session(session).param("month", "2024-02"), 200);
        assertThat(selected.get("periodStart").asString()).isEqualTo("2024-02-01");
        assertThat(selected.get("periodEnd").asString()).isEqualTo("2024-02-29");
        assertThat(selected.get("income").decimalValue()).isEqualByComparingTo("120");
        assertThat(selected.get("expenses").decimalValue()).isEqualByComparingTo("20");
        assertThat(selected.get("currentBalance").decimalValue()).isEqualByComparingTo("100");
        var page = http(get("/api/transactions").session(session).param("month", "2024-02")
                .param("size", "1").param("categoryId", Long.toString(category)), 200);
        assertThat(page.get("totalElements").asInt()).isEqualTo(2);
        assertThat(page.get("items").get(0).get("name").asString()).isEqualTo("Last");
        var current = http(get("/api/financial-summary").session(session), 200);
        assertThat(current.get("periodStart").asString()).isEqualTo("2027-03-19");
        assertThat(current.get("currentBalance").decimalValue()).isEqualByComparingTo("8");
        assertThat(http(get("/api/transactions").session(session), 200).get("totalElements").asInt()).isEqualTo(5);
        assertThat(httpAs(OTHER, get("/api/financial-summary").param("month", "2024-02"), 200)
                .get("income").decimalValue()).isZero();
    }

    @Test
    void potBalancesIncludeOnlyEffectiveMovementsThroughTheSelectedMonthEnd() throws Exception {
        long pot = create("/api/savings-pots", Map.of("name", "Car", "color", "#112233", "targetAmount", 500)).get("id").asLong();
        jdbc.update("""
                INSERT INTO transactions(username,name,transaction_date,amount,source_type,pot_id) VALUES
                (?,'Car','2026-12-31',100,'POT',?), (?,'Car','2027-01-31',-20,'POT',?), (?,'Car','2027-02-01',30,'POT',?)
                """, OWNER, pot, OWNER, pot, OWNER, pot);
        var jan = read("/api/savings-pots?month=2027-01");
        assertThat(jan.get("items").get(0).get("balance").decimalValue()).isEqualByComparingTo("80");
        assertThat(jan.get("totalBalance").decimalValue()).isEqualByComparingTo("80");
        assertThat(read("/api/savings-pots?month=2026-11").get("items").size()).isZero();
        assertThat(httpAs(OTHER, get("/api/savings-pots").param("month", "2027-01"), 200).get("totalBalance").decimalValue()).isZero();
        jdbc.update("UPDATE savings_pots SET deleted_at=CURRENT_TIMESTAMP WHERE id=?", pot);
        assertThat(read("/api/savings-pots?month=2027-01").get("items").size()).isZero();
    }

    @Test
    void rejectsInvalidCalendarMonths() throws Exception {
        for (String path : List.of("/api/financial-summary", "/api/transactions", "/api/savings-pots")) {
            http(get(path).param("month", "2027-13"), 400);
        }
    }
}
