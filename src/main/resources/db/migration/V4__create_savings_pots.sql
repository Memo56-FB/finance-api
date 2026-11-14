CREATE TABLE savings_pots (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    username VARCHAR(254) NOT NULL REFERENCES users(username),
    name VARCHAR(100) NOT NULL CHECK (name <> '' AND name = btrim(name)),
    target_amount NUMERIC(19,2) NOT NULL CHECK (target_amount BETWEEN 0.01 AND 99999999999999999.99),
    balance NUMERIC(19,2) NOT NULL DEFAULT 0 CHECK (balance BETWEEN 0 AND 99999999999999999.99),
    color VARCHAR(7) NOT NULL CHECK (color ~ '^#[0-9A-F]{6}$'),
    deleted_at TIMESTAMPTZ
);

CREATE INDEX savings_pots_active_user_idx ON savings_pots (username, id) WHERE deleted_at IS NULL;
