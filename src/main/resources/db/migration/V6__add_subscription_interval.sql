ALTER TABLE recurring_payments
    ADD COLUMN interval_months SMALLINT NOT NULL DEFAULT 1,
    ADD CONSTRAINT recurring_payments_interval_range CHECK (interval_months BETWEEN 1 AND 12),
    ADD CONSTRAINT recurring_payments_installment_interval CHECK (type = 'SUBSCRIPTION' OR interval_months = 1);
