CREATE TABLE transfer_decisions (
  id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  idempotency_key  TEXT NOT NULL UNIQUE,
  request_hash     TEXT NOT NULL,
  from_account_id  UUID NOT NULL REFERENCES accounts(id),
  to_account_id    UUID NOT NULL REFERENCES accounts(id),
  amount_cents     BIGINT NOT NULL CHECK (amount_cents > 0),
  outcome          TEXT NOT NULL CHECK (outcome IN ('APPROVED','BLOCKED','HELD','SKIPPED')),
  reason_code      TEXT NOT NULL,
  reasons          TEXT NOT NULL DEFAULT '',      -- comma-separated ReasonFlag names
  risk_level       TEXT,
  confidence       NUMERIC(4,3),
  jev_model        TEXT,
  jev_latency_ms   BIGINT,
  jev_input_tokens INT,
  transaction_id   UUID UNIQUE REFERENCES transactions(id),
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT posted_iff_allowed CHECK ((outcome IN ('APPROVED','SKIPPED')) = (transaction_id IS NOT NULL))
);
CREATE INDEX idx_transfer_decisions_outcome ON transfer_decisions(outcome);
