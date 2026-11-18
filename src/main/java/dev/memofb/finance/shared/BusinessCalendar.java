package dev.memofb.finance.shared;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class BusinessCalendar {
    private final Clock clock;
    private final ZoneId zone;

    public BusinessCalendar(Clock clock, @Value("${finance.business-zone:America/Mexico_City}") String zone) {
        this.clock = clock;
        this.zone = ZoneId.of(zone);
    }

    public LocalDate today() { return clock.instant().atZone(zone).toLocalDate(); }
    public YearMonth month() { return YearMonth.from(today()); }

    public YearMonth month(String value) {
        if (value == null) return month();
        if (!value.matches("[0-9]{4}-(0[1-9]|1[0-2])")) throw FinancialException.badRequest("month must be YYYY-MM");
        return YearMonth.parse(value);
    }
}
