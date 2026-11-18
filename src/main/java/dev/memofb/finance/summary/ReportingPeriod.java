package dev.memofb.finance.summary;

import java.time.LocalDate;
import java.time.YearMonth;

record ReportingPeriod(LocalDate start, LocalDate endExclusive) {
    static ReportingPeriod containing(LocalDate today, SummarySettings settings) {
        var month = YearMonth.from(today);
        var start = anchor(month, settings.startDay());
        if (start.isAfter(today)) { month = month.minusMonths(1); start = anchor(month, settings.startDay()); }
        var end = anchor(month.plusMonths(1), settings.startDay());
        if (settings.frequency() == SummarySettings.Frequency.SEMIMONTHLY) {
            var middle = start.plusDays(15);
            if (today.isBefore(middle)) end = middle; else start = middle;
        }
        return new ReportingPeriod(start, end);
    }

    private static LocalDate anchor(YearMonth month, int day) { return month.atDay(Math.min(day, month.lengthOfMonth())); }
}
