package dev.memofb.finance.summary;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class SummaryRepository {
    private final JdbcTemplate jdbc;
    SummaryRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    SummarySettings settings(String owner) {
        return jdbc.query("SELECT frequency,start_day FROM financial_summary_preferences WHERE username=?",
                (r,i)->new SummarySettings(SummarySettings.Frequency.valueOf(r.getString(1)),r.getInt(2)),owner)
                .stream().findFirst().orElseGet(SummarySettings::defaults);
    }

    void save(String owner, SummarySettings settings) {
        jdbc.update("""
                INSERT INTO financial_summary_preferences(username,frequency,start_day) VALUES (?,?,?)
                ON CONFLICT(username) DO UPDATE SET frequency=excluded.frequency,start_day=excluded.start_day
                """,owner,settings.frequency().name(),settings.startDay());
    }

    FinancialSummaryResponse summary(String owner, ReportingPeriod period) {
        return jdbc.queryForObject("""
                SELECT COALESCE(sum(amount) FILTER(WHERE amount>0),0) AS income,
                       COALESCE(sum(-amount) FILTER(WHERE amount<0),0) AS expenses
                FROM effective_transactions WHERE username=? AND transaction_date>=? AND transaction_date<?
                """, (r,i)->{
                    var income=r.getBigDecimal("income"); var expenses=r.getBigDecimal("expenses");
                    return new FinancialSummaryResponse(period.start(),period.endExclusive().minusDays(1),income.subtract(expenses),income,expenses);
                },owner,period.start(),period.endExclusive());
    }
}
