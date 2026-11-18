ALTER TABLE savings_pots ADD CONSTRAINT savings_pots_owner_id UNIQUE (username, id);

CREATE TABLE categories (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    username VARCHAR(254) NOT NULL REFERENCES users(username),
    name VARCHAR(100) NOT NULL CHECK (name <> '' AND name = btrim(name)),
    deleted_at TIMESTAMPTZ,
    UNIQUE (username, id)
);
CREATE INDEX categories_active_owner_idx ON categories(username, id) WHERE deleted_at IS NULL;

CREATE TABLE budgets (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    username VARCHAR(254) NOT NULL REFERENCES users(username),
    category_id BIGINT NOT NULL,
    spending_limit NUMERIC(19,2) NOT NULL CHECK (spending_limit BETWEEN 0.01 AND 99999999999999999.99),
    color VARCHAR(7) NOT NULL CHECK (color ~ '^#[0-9A-F]{6}$'),
    deleted_at TIMESTAMPTZ,
    FOREIGN KEY (username, category_id) REFERENCES categories(username, id)
);
CREATE UNIQUE INDEX budgets_active_category_idx ON budgets(username, category_id) WHERE deleted_at IS NULL;

CREATE TABLE recurring_payments (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    username VARCHAR(254) NOT NULL REFERENCES users(username),
    name VARCHAR(100) NOT NULL CHECK (name <> '' AND name = btrim(name)),
    category_id BIGINT NOT NULL,
    type VARCHAR(12) NOT NULL CHECK (type IN ('SUBSCRIPTION', 'INSTALLMENT')),
    amount NUMERIC(19,2) NOT NULL CHECK (amount BETWEEN 0.01 AND 99999999999999999.99),
    first_due_date DATE NOT NULL,
    total_installments INTEGER CHECK (total_installments > 0),
    next_auto_sequence INTEGER NOT NULL DEFAULT 1 CHECK (next_auto_sequence > 0),
    deleted_at TIMESTAMPTZ,
    UNIQUE (username, id),
    FOREIGN KEY (username, category_id) REFERENCES categories(username, id),
    CHECK ((type = 'SUBSCRIPTION' AND total_installments IS NULL)
        OR (type = 'INSTALLMENT' AND total_installments IS NOT NULL))
);
CREATE INDEX recurring_payments_active_owner_idx ON recurring_payments(username, id) WHERE deleted_at IS NULL;

CREATE TABLE recurring_payment_occurrences (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    username VARCHAR(254) NOT NULL REFERENCES users(username),
    recurring_payment_id BIGINT NOT NULL,
    sequence_number INTEGER NOT NULL CHECK (sequence_number > 0),
    due_month DATE NOT NULL CHECK (extract(day FROM due_month) = 1),
    due_date DATE NOT NULL,
    status VARCHAR(4) NOT NULL CHECK (status IN ('PAID', 'VOID')),
    paid_at TIMESTAMPTZ,
    UNIQUE (username, id),
    UNIQUE (recurring_payment_id, sequence_number),
    UNIQUE (recurring_payment_id, due_month),
    FOREIGN KEY (username, recurring_payment_id) REFERENCES recurring_payments(username, id),
    CHECK (due_date >= due_month AND due_date < due_month + INTERVAL '1 month'),
    CHECK ((status = 'PAID' AND paid_at IS NOT NULL) OR (status = 'VOID' AND paid_at IS NULL))
);
CREATE INDEX recurring_occurrences_owner_month_idx ON recurring_payment_occurrences(username, due_month);

CREATE TABLE transactions (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    username VARCHAR(254) NOT NULL REFERENCES users(username),
    name VARCHAR(100) NOT NULL CHECK (name <> '' AND name = btrim(name)),
    category_id BIGINT,
    transaction_date DATE NOT NULL,
    amount NUMERIC(19,2) NOT NULL CHECK (amount BETWEEN -99999999999999999.99 AND 99999999999999999.99 AND amount <> 0),
    source_type VARCHAR(17) NOT NULL CHECK (source_type IN ('MANUAL', 'POT', 'RECURRING_PAYMENT')),
    pot_id BIGINT,
    recurring_occurrence_id BIGINT,
    opening_balance BOOLEAN NOT NULL DEFAULT false,
    deleted_at TIMESTAMPTZ,
    FOREIGN KEY (username, category_id) REFERENCES categories(username, id),
    FOREIGN KEY (username, pot_id) REFERENCES savings_pots(username, id),
    FOREIGN KEY (username, recurring_occurrence_id) REFERENCES recurring_payment_occurrences(username, id),
    CHECK ((source_type = 'MANUAL' AND category_id IS NOT NULL AND pot_id IS NULL AND recurring_occurrence_id IS NULL)
        OR (source_type = 'POT' AND pot_id IS NOT NULL AND recurring_occurrence_id IS NULL)
        OR (source_type = 'RECURRING_PAYMENT' AND category_id IS NOT NULL AND pot_id IS NULL AND recurring_occurrence_id IS NOT NULL)),
    CHECK (NOT opening_balance OR source_type = 'POT')
);
CREATE INDEX transactions_owner_date_idx ON transactions(username, transaction_date, id) WHERE deleted_at IS NULL;
CREATE INDEX transactions_category_date_idx ON transactions(username, category_id, transaction_date) WHERE deleted_at IS NULL;
CREATE INDEX transactions_pot_idx ON transactions(pot_id);
CREATE UNIQUE INDEX transactions_active_occurrence_idx ON transactions(recurring_occurrence_id)
    WHERE deleted_at IS NULL AND amount < 0;

CREATE TABLE financial_summary_preferences (
    username VARCHAR(254) PRIMARY KEY REFERENCES users(username),
    frequency VARCHAR(11) NOT NULL CHECK (frequency IN ('MONTHLY', 'SEMIMONTHLY')),
    start_day SMALLINT NOT NULL CHECK (start_day BETWEEN 1 AND 31)
);

INSERT INTO transactions (username, name, transaction_date, amount, source_type, pot_id, opening_balance)
SELECT username, name, (CURRENT_TIMESTAMP AT TIME ZONE 'America/Mexico_City')::date, balance, 'POT', id, true
FROM savings_pots WHERE balance <> 0;

CREATE VIEW effective_transactions AS
SELECT t.* FROM transactions t LEFT JOIN savings_pots p ON p.id = t.pot_id AND p.username = t.username
WHERE t.deleted_at IS NULL AND (t.pot_id IS NULL OR p.deleted_at IS NULL);
