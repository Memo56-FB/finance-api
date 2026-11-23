LOCK TABLE users, savings_pots, categories, budgets, recurring_payments,
    recurring_payment_occurrences, transactions, financial_summary_preferences IN SHARE ROW EXCLUSIVE MODE;

DO $$
DECLARE
    old_login text;
    base_name text;
    new_login text;
    suffix bigint;
BEGIN
    FOR old_login IN SELECT username FROM users
        WHERE username COLLATE "C" !~ '^[a-z0-9._-]+$' ORDER BY username COLLATE "C"
    LOOP
        base_name := regexp_replace(split_part(old_login, '@', 1) COLLATE "C", '[^a-z0-9._-]+', '_', 'g');
        new_login := coalesce(nullif(base_name, ''), 'user');
        base_name := new_login;
        suffix := 1;
        WHILE EXISTS (SELECT 1 FROM users WHERE username = new_login) LOOP
            suffix := suffix + 1;
            new_login := left(base_name, 253 - length(suffix::text)) || '_' || suffix;
        END LOOP;

        -- Foreign keys are checked at statement end, after every owner reference has moved.
        WITH pots AS (UPDATE savings_pots SET username = new_login WHERE username = old_login),
             cats AS (UPDATE categories SET username = new_login WHERE username = old_login),
             limits AS (UPDATE budgets SET username = new_login WHERE username = old_login),
             payments AS (UPDATE recurring_payments SET username = new_login WHERE username = old_login),
             occurrences AS (UPDATE recurring_payment_occurrences SET username = new_login WHERE username = old_login),
             ledger AS (UPDATE transactions SET username = new_login WHERE username = old_login),
             preferences AS (UPDATE financial_summary_preferences SET username = new_login WHERE username = old_login)
        UPDATE users SET username = new_login WHERE username = old_login;
    END LOOP;
END $$;

ALTER TABLE users ADD CONSTRAINT users_username_format
    CHECK (username COLLATE "C" ~ '^[a-z0-9._-]+$');
