package dev.memofb.finance.summary;

import jakarta.validation.constraints.*;

public record SummarySettings(@NotNull Frequency frequency, @NotNull @Min(1) @Max(31) Integer startDay) {
    public enum Frequency { MONTHLY, SEMIMONTHLY }
    public static SummarySettings defaults() { return new SummarySettings(Frequency.MONTHLY, 1); }
}
