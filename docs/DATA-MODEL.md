# Data model

Schema: [`V1__initial_schema.sql`](../src/main/resources/db/migration/V1__initial_schema.sql).
Seed data: [`V2__seed_demo_users.sql`](../src/main/resources/db/migration/V2__seed_demo_users.sql).

Money is integer **paise** in every layer — `BIGINT` in the column, `long` in Java, an
integer in JSON. No decimal type and no floating point at any boundary.

---

## Tables

### `users`
| Column | Notes |
|---|---|
| `id` | `BIGSERIAL` |
| `external_id` | UNIQUE. Human-readable handle (`alice`). |
| `bearer_token` | UNIQUE. The whole of authentication. |

Seeded by Flyway; there is no registration endpoint.

### `wallets`
| Column | Notes |
|---|---|
| `id` | `BIGSERIAL`. The public wallet id. |
| `user_id` | **UNIQUE**, FK to `users`. One wallet per user. |
| `balance_paise` | `BIGINT NOT NULL`, **`CHECK (balance_paise >= 0)`**. |

### `transfers`
| Column | Notes |
|---|---|
| `id` | `BIGSERIAL`. Internal, used by foreign keys. |
| `public_id` | UNIQUE `UUID`. What the API exposes. |
| `idempotency_key` | Client-supplied. |
| `created_by_user` | FK to `users`. Scopes the key to its caller. |
| `from_wallet_id`, `to_wallet_id` | FK to `wallets`. |
| `amount_paise` | `CHECK (> 0)`. |
| `status` | `PENDING` \| `COMPLETED` \| `DECLINED_INSUFFICIENT_FUNDS`. |
| `request_hash` | SHA-256 of `(from, to, amount)`. |

Two ids on purpose: an internal `BIGSERIAL` keeps foreign keys and indexes compact, while
the public `UUID` means API consumers cannot guess ids or infer how many transfers the
system has processed.

`PENDING` is transient. The row is inserted with it and updated to a terminal status in the
same transaction, so a committed `PENDING` row is not reachable.

### `ledger_entries`
| Column | Notes |
|---|---|
| `transfer_id` | FK, **nullable** — `DEPOSIT` entries have no originating transfer. |
| `wallet_id` | FK to `wallets`. |
| `delta_paise` | Negative for a debit, positive for a credit. `CHECK (<> 0)`. |
| `entry_type` | `TRANSFER` \| `DEPOSIT`. |

Append-only. Nothing updates or deletes a ledger entry.

---

## Which constraint defends which invariant

| Constraint | Invariant | What it stops |
|---|---|---|
| `wallets.balance_paise CHECK (>= 0)` | **No overdraft** | A negative balance, from any source — including a future endpoint or a hand-written `UPDATE`, not just this code path. |
| `wallets.user_id UNIQUE` | **Race-free get-or-create** | A second wallet for the same user. Concurrent creators collide here; `ON CONFLICT DO NOTHING` turns the collision into a no-op and everyone reads the one row that exists. |
| `transfers UNIQUE (created_by_user, idempotency_key)` | **Exactly-once** | A second application of the same key. Committed in the same transaction as the money. |
| `transfers CHECK (amount_paise > 0)` | **Conservation** | A zero or negative transfer, which would let a caller debit the *destination* by sending a negative amount. |
| `transfers CHECK (from_wallet_id <> to_wallet_id)` | **Conservation** | A self-transfer, which would deadlock against its own row lock. |
| `ledger_entries CHECK (delta_paise <> 0)` | Audit integrity | Meaningless rows. |
| `ledger_entries` transfer-link `CHECK` | Audit integrity | A `TRANSFER` entry with no transfer, or a `DEPOSIT` with one. |

The pattern throughout: the application enforces each rule *and* the database enforces it
independently. The application's version produces a good error message; the database's
version is the one that still holds when the application is wrong.

---

## Proving conservation two ways

```sql
-- 1. Balances. Must be unchanged by any number of transfers.
SELECT SUM(balance_paise) FROM wallets;

-- 2. The ledger, which never reads the balance column. Must be exactly zero.
SELECT SUM(delta_paise) FROM ledger_entries WHERE entry_type = 'TRANSFER';
```

The second is the stronger check: it would still catch a bug that corrupted every balance
consistently. Both are asserted by the burst script and the test suite.

`DEPOSIT` entries are excluded from the second sum by design — deposits are the money-in
boundary and are the only operation that legitimately changes the system-wide total.
See [`WalletService.deposit`](../src/main/java/com/shiva/wallet/service/WalletService.java).
