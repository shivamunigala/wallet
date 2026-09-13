# Code map

Every source file, what it does, and why it exists. **Start here** before reading code.

The "why" column is the part worth reading. Most of these files are ordinary; the handful
that carry the correctness of the service are marked **[core]** and each has a deep-dive
document.

---

## Where the correctness lives

If you only read four files, read these:

| File | Why it matters |
|---|---|
| [`TransferExecutor.java`](../src/main/java/com/shiva/wallet/service/TransferExecutor.java) | The single transaction in which money moves. Statement order here *is* the correctness argument. → [CONCURRENCY.md](CONCURRENCY.md) |
| [`WalletRepository.java`](../src/main/java/com/shiva/wallet/repository/WalletRepository.java) | The hand-written SQL that enforces no-overdraft and race-free creation. |
| [`TransferService.java`](../src/main/java/com/shiva/wallet/service/TransferService.java) | Idempotent replay resolution, which must sit *outside* the transaction. |
| [`V1__initial_schema.sql`](../src/main/resources/db/migration/V1__initial_schema.sql) | The constraints that hold even if the Java is wrong. → [DATA-MODEL.md](DATA-MODEL.md) |

---

## `service/` — business rules and transactions

| File | Responsibility | Why it exists |
|---|---|---|
| [`TransferExecutor.java`](../src/main/java/com/shiva/wallet/service/TransferExecutor.java) **[core]** | The `@Transactional` money movement: lock, claim key, debit, credit, ledger. | Separate from `TransferService` because the unique-constraint violation that signals a replay must be caught *outside* the transaction — a self-invocation would bypass the Spring proxy and leave the caller holding an aborted transaction. → [CONCURRENCY.md](CONCURRENCY.md) |
| [`TransferService.java`](../src/main/java/com/shiva/wallet/service/TransferService.java) **[core]** | Validation, replay resolution, metrics. Deliberately not transactional. | Owns everything around the transaction. Handles both replay paths: the fast indexed lookup, and the constraint violation from a same-key race. |
| [`WalletService.java`](../src/main/java/com/shiva/wallet/service/WalletService.java) | Get-or-create, ownership checks, deposits. | Get-or-create is race-free with no application lock — the database arbitrates. |
| [`RequestHasher.java`](../src/main/java/com/shiva/wallet/service/RequestHasher.java) | SHA-256 fingerprint of `(from, to, amount)`. | Distinguishes an honest retry from a key collision. Storing a hash avoids having to canonicalise and store JSON. |

## `repository/` — the SQL

| File | Responsibility | Why it exists |
|---|---|---|
| [`WalletRepository.java`](../src/main/java/com/shiva/wallet/repository/WalletRepository.java) **[core]** | `insertIfAbsent`, `findByIdForUpdate`, `tryDebit`, `credit`, balance reads. | SQL written by hand rather than left to Hibernate dirty checking, because the exact shape of these statements is the invariant enforcement. |
| [`TransferRepository.java`](../src/main/java/com/shiva/wallet/repository/TransferRepository.java) | Transfer lookup by public id and by idempotency key. | Transfers double as the idempotency store — see [DESIGN-DECISIONS.md](DESIGN-DECISIONS.md#where-idempotency-lives). |
| [`LedgerEntryRepository.java`](../src/main/java/com/shiva/wallet/repository/LedgerEntryRepository.java) | Append-only audit writes, plus `transferEntriesNetSum`. | That sum proves conservation **without reading the balance column**, catching bugs a balance-vs-balance check would miss. |
| [`UserRepository.java`](../src/main/java/com/shiva/wallet/repository/UserRepository.java) | Bearer token → user. | Users are seeded by Flyway; there is no registration API. |

## `domain/` — entities and enums

| File | Responsibility | Why it exists |
|---|---|---|
| [`Wallet.java`](../src/main/java/com/shiva/wallet/domain/Wallet.java) | Wallet read model. | **Never mutated through JPA.** Every balance change goes through `WalletRepository` SQL so the predicate and row lock always apply. Treat as read-only. |
| [`Transfer.java`](../src/main/java/com/shiva/wallet/domain/Transfer.java) | A transfer attempt, successful or declined. | Also the idempotency record. Two ids: internal `BIGSERIAL` for foreign keys, public `UUID` for the API so row counts aren't leaked. |
| [`LedgerEntry.java`](../src/main/java/com/shiva/wallet/domain/LedgerEntry.java) | One half of a balance change. | Independent, append-only proof of conservation. |
| [`User.java`](../src/main/java/com/shiva/wallet/domain/User.java) | A caller. | Auth is thin on purpose; the brief says auth sophistication is not graded. |
| [`TransferStatus.java`](../src/main/java/com/shiva/wallet/domain/TransferStatus.java) | `PENDING` / `COMPLETED` / `DECLINED_INSUFFICIENT_FUNDS`. | `PENDING` is transient — it is never visible in committed data. |
| [`LedgerEntryType.java`](../src/main/java/com/shiva/wallet/domain/LedgerEntryType.java) | `TRANSFER` / `DEPOSIT`. | `TRANSFER` entries sum to zero system-wide; `DEPOSIT` is the money-in boundary and is the only thing that legitimately changes the total. |

## `domain/exception/` — error taxonomy

| File | Maps to | Why it exists |
|---|---|---|
| [`ApiException.java`](../src/main/java/com/shiva/wallet/domain/exception/ApiException.java) | — | Base carrying status + stable code, so the handler needs no type switching. |
| [`IdempotencyConflictException.java`](../src/main/java/com/shiva/wallet/domain/exception/IdempotencyConflictException.java) | `409` | The case the brief calls out: same key, different body. Never a second debit. |
| [`ForbiddenException.java`](../src/main/java/com/shiva/wallet/domain/exception/ForbiddenException.java) | `403` | Caller does not own the source wallet. Distinct from 401 so tests can tell "bad token" from "wrong wallet". |
| [`UnauthorizedException.java`](../src/main/java/com/shiva/wallet/domain/exception/UnauthorizedException.java) | `401` | Missing or unknown token. |
| [`NotFoundException.java`](../src/main/java/com/shiva/wallet/domain/exception/NotFoundException.java) | `404` | Unknown wallet or transfer. |
| [`InvalidRequestException.java`](../src/main/java/com/shiva/wallet/domain/exception/InvalidRequestException.java) | `400` | Semantic problems bean validation can't express, e.g. `from == to`. |

Insufficient funds is deliberately **not** an exception — it is a recorded outcome, so it
survives as a durable, replayable transfer row. See [CONCURRENCY.md](CONCURRENCY.md).

## `web/` — HTTP surface

| File | Responsibility | Why it exists |
|---|---|---|
| [`TransferController.java`](../src/main/java/com/shiva/wallet/web/TransferController.java) | `POST /transfers`, `GET /transfers/{id}`. | A decline returns `422` carrying the whole transfer, not a bare error — the transfer genuinely exists and replays identically. |
| [`WalletController.java`](../src/main/java/com/shiva/wallet/web/WalletController.java) | `POST /wallets`, `GET /wallets/{id}`, `POST /wallets/{id}/deposit`. | Get-or-create always returns `200`, never `201`-on-create: under the concurrent-creation probe a created/found status would be a race outcome rather than a property of the system. |
| [`ApiExceptionHandler.java`](../src/main/java/com/shiva/wallet/web/ApiExceptionHandler.java) | Exceptions → uniform JSON error body. | Only unexpected errors log at ERROR. Declines and conflicts are normal under load; logging them as errors would drown the real signal during a burst. |
| [`CallerContext.java`](../src/main/java/com/shiva/wallet/web/CallerContext.java) | Current caller, in a thread local. | Safe because the money path is synchronous blocking JDBC with no async hand-off. Always cleared in a `finally`, since Tomcat threads are pooled. |
| [`filter/CorrelationIdFilter.java`](../src/main/java/com/shiva/wallet/web/filter/CorrelationIdFilter.java) | Correlation id → MDC → every log line + response header. | The only way to follow one request through a log stream with hundreds of interleaved concurrent transfers. |
| [`filter/BearerTokenAuthFilter.java`](../src/main/java/com/shiva/wallet/web/filter/BearerTokenAuthFilter.java) | `Authorization: Bearer` → `User`. | Leaves unauthenticated requests alone rather than rejecting, so `/actuator/**` stays reachable for health checks without a second path allowlist. |
| [`dto/`](../src/main/java/com/shiva/wallet/web/dto) | Request and response bodies. | `amount_paise` is a boxed `Long` so a missing field fails validation instead of defaulting to zero. |

## `config/`

| File | Responsibility | Why it exists |
|---|---|---|
| [`WalletMetrics.java`](../src/main/java/com/shiva/wallet/config/WalletMetrics.java) | Domain counters for Prometheus. | Counters resolved once at construction, so the hot path costs an increment. |
| [`DatabaseUrlEnvironmentPostProcessor.java`](../src/main/java/com/shiva/wallet/config/DatabaseUrlEnvironmentPostProcessor.java) | `postgres://user:pass@host/db` → JDBC url + username + password. | Neon and Render hand out URLs JDBC cannot parse. Runs as an `EnvironmentPostProcessor` so the translation happens before the datasource is built. Registered in [`spring.factories`](../src/main/resources/META-INF/spring.factories). |

## Resources

| File | Responsibility | Why it exists |
|---|---|---|
| [`V1__initial_schema.sql`](../src/main/resources/db/migration/V1__initial_schema.sql) **[core]** | Tables and constraints. | Each constraint defends one invariant; the database is the last line if the Java is wrong. → [DATA-MODEL.md](DATA-MODEL.md) |
| [`V2__seed_demo_users.sql`](../src/main/resources/db/migration/V2__seed_demo_users.sql) | Four demo users with fixed tokens. | So the burst script runs against a fresh database with zero setup. |
| [`application.yml`](../src/main/resources/application.yml) | Datasource, pool, Flyway, actuator, metrics. | Carries the reasoning for the small pool and the Flyway lock-retry / pooled-endpoint warning. |
| [`logback-spring.xml`](../src/main/resources/logback-spring.xml) | JSON logs to stdout; plain console under the `local` profile. | Structured logs with the correlation id are a graded requirement. |

## Tests

| File | Proves | Why it exists |
|---|---|---|
| [`support/AbstractPostgresTest.java`](../src/test/java/com/shiva/wallet/support/AbstractPostgresTest.java) | — | Real Postgres via Testcontainers, plus a barrier helper that fires N requests at one instant. H2 would not reproduce `FOR UPDATE`, `ON CONFLICT`, or the 0-row-UPDATE behaviour, so tests against it would pass while proving nothing. |
| [`ConservationTest.java`](../src/test/java/com/shiva/wallet/ConservationTest.java) | Invariants 1 & 2 | Concurrent transfers in both directions. **Asserts on status codes as well as balances** — an earlier version checked only money and stayed green while most requests were deadlocking, because conservation holds trivially when transactions roll back. |
| [`IdempotencyTest.java`](../src/test/java/com/shiva/wallet/IdempotencyTest.java) | Invariant 3 | Retry storm, sequential replay, and same-key/different-body conflict. |
| [`GetOrCreateRaceTest.java`](../src/test/java/com/shiva/wallet/GetOrCreateRaceTest.java) | Invariant 4 | N simultaneous creates yield one wallet. |
| [`OverdraftTest.java`](../src/test/java/com/shiva/wallet/OverdraftTest.java) | Invariant 2 + auth | A decline must be durable, addressable and replayable — not a transient error. |
| [`WalletApplicationTests.java`](../src/test/java/com/shiva/wallet/WalletApplicationTests.java) | Smoke | Context starts, migrations apply, health is public, domain counters are exported. |

## Operations

| File | Responsibility | Why it exists |
|---|---|---|
| [`scripts/burst.py`](../scripts/burst.py) | Reproduces all four invariants against any URL. | Python 3 stdlib only — no `pip install`, no `jq`. Uses a barrier so requests fire simultaneously rather than merely concurrently. |
| [`scripts/burst.sh`](../scripts/burst.sh) | One-command wrapper. | The brief asks for one command. |
| [`Dockerfile`](../Dockerfile) | Multi-stage, non-root, `HEALTHCHECK`. | Jammy over Alpine because Temurin 8 Alpine is amd64-only and this builds on arm64 too. |
| [`docker-compose.yml`](../docker-compose.yml) | App + Postgres in one command. | App waits on the database's own health check, so a cold `up` needs no retries or sleeps. |
| [`render.yaml`](../render.yaml) | Render blueprint. | `DATABASE_URL` is `sync: false` — it is a secret, entered in the dashboard, never committed. |
