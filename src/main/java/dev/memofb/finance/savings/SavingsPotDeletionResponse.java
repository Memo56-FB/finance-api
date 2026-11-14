package dev.memofb.finance.savings;

import java.time.Instant;

record SavingsPotDeletionResponse(long id, Instant deletedAt, Instant restoreUntil) {
}
