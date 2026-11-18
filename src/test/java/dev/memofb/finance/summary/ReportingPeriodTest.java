package dev.memofb.finance.summary;

import java.time.*;
import org.junit.jupiter.api.Test;
import dev.memofb.finance.shared.BusinessCalendar;
import static dev.memofb.finance.summary.SummarySettings.Frequency.*;
import static org.assertj.core.api.Assertions.*;

class ReportingPeriodTest {
    @Test
    void resolvesMonthlyAndOffsetSemimonthlyCuts() {
        assertPeriod("2027-01-20",MONTHLY,4,"2027-01-04","2027-02-04");
        assertPeriod("2027-03-20",SEMIMONTHLY,4,"2027-03-19","2027-04-04");
        assertPeriod("2027-01-16",SEMIMONTHLY,1,"2027-01-16","2027-02-01");
        assertPeriod("2028-02-29",MONTHLY,31,"2028-02-29","2028-03-31");
        assertPeriod("2027-02-27",MONTHLY,31,"2027-01-31","2027-02-28");
    }
    @Test
    void partitionsEveryDayForAllAnchorsAcrossLeapYearsAndYearBoundaries() {
        for(int day=1;day<=31;day++) for(var frequency:SummarySettings.Frequency.values()) {
            var settings=new SummarySettings(frequency,day);
            var date=LocalDate.of(2026,12,1);var end=LocalDate.of(2029,1,1);
            while(date.isBefore(end)) {
                var period=ReportingPeriod.containing(date,settings);
                assertThat(period.start()).isBeforeOrEqualTo(date);
                assertThat(period.endExclusive()).isAfter(date);
                var next=ReportingPeriod.containing(period.endExclusive(),settings);
                assertThat(next.start()).isEqualTo(period.endExclusive());
                assertThat(period.endExclusive().toEpochDay()-period.start().toEpochDay()).isBetween(13L,31L);
                date=period.endExclusive();
            }
        }
    }
    @Test
    void businessDateUsesExplicitZoneAndRejectsMalformedMonth() {
        var clock=Clock.fixed(Instant.parse("2027-04-01T01:00:00Z"),ZoneOffset.UTC);
        var calendar=new BusinessCalendar(clock,"America/Mexico_City");
        assertThat(calendar.today()).isEqualTo(LocalDate.of(2027,3,31));
        assertThat(calendar.month()).isEqualTo(YearMonth.of(2027,3));
        assertThatThrownBy(()->calendar.month("2027-13")).hasMessageContaining("YYYY-MM");
    }
    private void assertPeriod(String today,SummarySettings.Frequency frequency,int day,String start,String end) {
        assertThat(ReportingPeriod.containing(LocalDate.parse(today),new SummarySettings(frequency,day)))
                .isEqualTo(new ReportingPeriod(LocalDate.parse(start),LocalDate.parse(end)));
    }
}
