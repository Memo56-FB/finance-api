package dev.memofb.finance.recurring;

import java.time.LocalDate;
import java.time.Instant;

public record PaymentOccurrence(long id, long recurringPaymentId, int sequenceNumber, LocalDate dueDate, String status, Instant paidAt) { }
