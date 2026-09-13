-- Wallet service schema.
--
-- Every constraint here defends one of the four graded invariants. The application
-- enforces them too, but the database is the last line: a bug in Java cannot create
-- money, overdraw a wallet, or apply an idempotency key twice.
--
-- See docs/DATA-MODEL.md for the constraint-to-invariant mapping.

CREATE TABLE users (
    id            BIGSERIAL    PRIMARY KEY,
    external_id   TEXT         NOT NULL UNIQUE,
    bearer_token  TEXT         NOT NULL UNIQUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- One wallet per user. The UNIQUE constraint on user_id is what makes
-- get-or-create race-free: concurrent inserts collide instead of duplicating.
CREATE TABLE wallets (
    id             BIGSERIAL    PRIMARY KEY,
    user_id        BIGINT       NOT NULL UNIQUE REFERENCES users (id),
    balance_paise  BIGINT       NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_wallets_no_overdraft CHECK (balance_paise >= 0)
);

CREATE TABLE transfers (
    id                BIGSERIAL    PRIMARY KEY,
    public_id         UUID         NOT NULL UNIQUE,
    idempotency_key   TEXT         NOT NULL,
    created_by_user   BIGINT       NOT NULL REFERENCES users (id),
    from_wallet_id    BIGINT       NOT NULL REFERENCES wallets (id),
    to_wallet_id      BIGINT       NOT NULL REFERENCES wallets (id),
    amount_paise      BIGINT       NOT NULL,
    status            TEXT         NOT NULL,
    request_hash      TEXT         NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),

    -- Exactly-once. Scoped per caller so two users cannot collide on a common
    -- key like "retry-1". Committed in the same transaction as the money movement.
    CONSTRAINT uq_transfers_idem UNIQUE (created_by_user, idempotency_key),

    CONSTRAINT chk_transfers_amount_positive CHECK (amount_paise > 0),
    CONSTRAINT chk_transfers_distinct_wallets CHECK (from_wallet_id <> to_wallet_id),
    -- PENDING is transient: the row is inserted first so that concurrent requests with
    -- the same key collide on the unique index before any money moves, then updated to a
    -- terminal status in the same transaction. A committed PENDING row is impossible.
    CONSTRAINT chk_transfers_status CHECK (status IN ('PENDING', 'COMPLETED', 'DECLINED_INSUFFICIENT_FUNDS'))
);

CREATE INDEX idx_transfers_from_wallet ON transfers (from_wallet_id);
CREATE INDEX idx_transfers_to_wallet   ON transfers (to_wallet_id);

-- Append-only audit trail, written in the same transaction as the balance change.
-- For a transfer the two rows sum to zero, which is an independent proof of
-- conservation that does not trust the balance column.
CREATE TABLE ledger_entries (
    id           BIGSERIAL    PRIMARY KEY,
    transfer_id  BIGINT       REFERENCES transfers (id),
    wallet_id    BIGINT       NOT NULL REFERENCES wallets (id),
    delta_paise  BIGINT       NOT NULL,
    entry_type   TEXT         NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT chk_ledger_entry_type CHECK (entry_type IN ('TRANSFER', 'DEPOSIT')),
    CONSTRAINT chk_ledger_delta_nonzero CHECK (delta_paise <> 0),
    -- A TRANSFER entry always belongs to a transfer; a DEPOSIT never does.
    CONSTRAINT chk_ledger_transfer_link CHECK (
        (entry_type = 'TRANSFER' AND transfer_id IS NOT NULL)
        OR (entry_type = 'DEPOSIT' AND transfer_id IS NULL)
    )
);

CREATE INDEX idx_ledger_wallet   ON ledger_entries (wallet_id);
CREATE INDEX idx_ledger_transfer ON ledger_entries (transfer_id);
