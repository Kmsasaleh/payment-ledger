CREATE TABLE accounts (
                          id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                          name        TEXT NOT NULL,
                          type        TEXT NOT NULL CHECK (type IN ('USER', 'SYSTEM')),
                          currency    CHAR(3) NOT NULL,
                          created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
                          version     BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE transactions (
                              id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                              idempotency_key  TEXT NOT NULL UNIQUE,
                              description      TEXT,
                              created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE entries (
                         id              BIGSERIAL PRIMARY KEY,
                         transaction_id  UUID NOT NULL REFERENCES transactions(id),
                         account_id      UUID NOT NULL REFERENCES accounts(id),
                         amount_cents    BIGINT NOT NULL CHECK (amount_cents <> 0),
                         created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_entries_account_id ON entries(account_id);
CREATE INDEX idx_entries_transaction_id ON entries(transaction_id);