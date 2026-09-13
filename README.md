# wallet

A wallet service with peer-to-peer transfers, built for the Paytm PML Round 2 exercise.

The point of the exercise is not the API — it is whether the service stays correct under
concurrency and failure, whether it genuinely deploys and can be observed, and whether the
reasoning holds up. So the interesting reading is in [`docs/`](docs/), not in the endpoint
list.

## The four invariants

| | Invariant | Mechanism | Backstop |
|---|---|---|---|
| 1 | **Conservation** — transfers never create or destroy money | Debit and credit in one transaction | Ledger entries sum to zero, independently of balances |
| 2 | **No overdraft** — a balance never goes negative | Conditional `UPDATE … WHERE balance >= :amount`; zero rows is a clean decline | `CHECK (balance_paise >= 0)` on the table |
| 3 | **Exactly-once** — a repeated idempotency key applies once | `UNIQUE (caller, key)` committed in the *same transaction* as the money | Request hash → `409` on same key, different body |
| 4 | **Race-free get-or-create** — concurrent creates yield one wallet | `INSERT … ON CONFLICT DO NOTHING` | `UNIQUE (user_id)` |

All four are proved twice: by a Testcontainers suite against real PostgreSQL, and by a
one-command burst script against a running deployment.

## Quick start

```bash
docker-compose up --build
```

```bash
./scripts/burst.sh http://localhost:8080
```

```
[1/4] Concurrent get-or-create: 25 simultaneous POST /wallets
  PASS every caller received one and the same wallet id
  ...
========================================================================
All 15 assertions passed.
========================================================================
```

## API

Money is always integer **paise**. Auth is a bearer token per user.

| Method | Path | Notes |
|---|---|---|
| `POST` | `/wallets` | Get-or-create for the caller. Always `200` — never `201`, because under concurrent creation the status would be a race outcome rather than a property of the system |
| `GET` | `/wallets/{id}` | Balance. Caller must own it |
| `POST` | `/wallets/{id}/deposit` | Money-in boundary, so scenarios have funds to move. Not part of the graded surface |
| `POST` | `/transfers` | `from`, `to`, `amount_paise`, `idempotency_key` |
| `GET` | `/transfers/{id}` | Status |
| `GET` | `/actuator/health`, `/actuator/prometheus` | Health and metrics |

`201` applied · `422` declined, with the transfer body · `409` key reused with a different
body · `503` transient contention, retry with the same key · `403` not your wallet.

A declined transfer is a real, durable, addressable transfer — not an error. Replaying its
key returns the same decline rather than re-attempting the debit.

## Documentation

| | |
|---|---|
| [CODE-MAP.md](docs/CODE-MAP.md) | Every source file: what it does, why it exists |
| [DESIGN-DECISIONS.md](docs/DESIGN-DECISIONS.md) | The write-up: mechanism, rejected alternatives, idempotency, CAP |
| [CONCURRENCY.md](docs/CONCURRENCY.md) | The transfer transaction, step by step |
| [DATA-MODEL.md](docs/DATA-MODEL.md) | Schema, and which invariant each constraint defends |
| [ARCHITECTURE.md](docs/ARCHITECTURE.md) | Layers and request flow |
| [OPERATIONS.md](docs/OPERATIONS.md) | Run, deploy, observe, troubleshoot |

If you read one thing: [the deadlock that foreign keys caused](docs/DESIGN-DECISIONS.md#the-bug-this-ordering-originally-had--worth-reading).

## Stack

| | |
|---|---|
| Language | Java 8 |
| Framework | Spring Boot 2.7.18 |
| Database | PostgreSQL, Flyway migrations |
| Observability | JSON logs with a correlation id; Micrometer → Prometheus |
| Tests | JUnit 5 + Testcontainers, against real PostgreSQL |
| Deployment | Docker (multi-stage, non-root, `HEALTHCHECK`) → Render + Neon |

Cost to run: **₹0**, entirely on free tiers.

## Building from source

Java 8 only. Pin `JAVA_HOME` if the machine has several JDKs:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 1.8)
./mvnw clean verify
```

Tests require a running Docker daemon. See [OPERATIONS.md](docs/OPERATIONS.md) for Colima
specifics and the full deployment runbook.
