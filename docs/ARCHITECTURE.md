# Architecture

A single Spring Boot service over one PostgreSQL database. No queue, no cache, no second
system of record — that is a deliberate choice, not an omission
([why](DESIGN-DECISIONS.md#heavier-alternatives-and-why-they-were-rejected)).
How it behaves once deployed, and why, is in [PERFORMANCE.md](PERFORMANCE.md).

```
   client
     |
     |  Authorization: Bearer <token>
     v
+--------------------------------------------------+
|  CorrelationIdFilter    -> MDC, X-Correlation-Id  |
|  BearerTokenAuthFilter  -> CallerContext          |
+--------------------------------------------------+
     |
     v
  Controllers          validate shape, map status codes
     |
     v
  TransferService      NOT transactional
     |                 validation, replay resolution, metrics
     v
  TransferExecutor     @Transactional  <-- all correctness lives here
     |
     v
  Repositories         hand-written SQL: FOR UPDATE, conditional debit, upsert
     |
     v
  PostgreSQL           constraints as the last line of defence
```

## Layers, and what each is responsible for

**Filters** — correlation id into the MDC so every log line carries it, and bearer token to
a `User`. Unauthenticated requests are passed through rather than rejected, so
`/actuator/**` stays reachable for health checks and Prometheus without a second allowlist.

**Controllers** — shape validation and status-code mapping only. No business logic. A
declined transfer returns `422` with the whole transfer body, because the transfer genuinely
exists and is addressable.

**`TransferService`** — deliberately *not* transactional. It validates, resolves idempotent
replays, and counts metrics. It must live outside the transaction because the
unique-constraint violation that signals a replay aborts the Postgres transaction, and
nothing can be read on that connection until it rolls back.

**`TransferExecutor`** — the one `@Transactional` method where money moves. Statement order
here is the correctness argument; see [CONCURRENCY.md](CONCURRENCY.md).

**Repositories** — hand-written SQL rather than Hibernate dirty checking, because the exact
shape of these statements is what enforces the invariants.

**PostgreSQL** — `CHECK` and `UNIQUE` constraints that hold even if the Java is wrong.
See [DATA-MODEL.md](DATA-MODEL.md).

## A request, end to end

`POST /transfers` with `{from, to, amount_paise, idempotency_key}`:

1. `CorrelationIdFilter` stamps an id; `BearerTokenAuthFilter` resolves the caller.
2. `TransferController` validates the body shape.
3. `TransferService` checks amount and wallet ids, confirms the caller owns the source
   wallet, and computes the request hash.
4. It looks for an existing transfer under `(caller, key)`. If found, that is a replay —
   same body returns the original result, different body returns `409`.
5. Otherwise `TransferExecutor` runs the transaction: lock both wallets in ascending id
   order, insert the transfer claiming the key, conditional debit, credit, ledger, status.
6. A unique-constraint violation on the way means a concurrent request won the race; the
   transaction rolls back and step 4's logic re-runs against the winner's row.
7. `201` if money moved, `422` if declined. Both carry the transfer body, and both replay
   identically.

## Threading and the database pool

Everything on the money path is synchronous blocking JDBC holding row locks. There is no
async hand-off, which is why a thread local is a safe place for the caller identity.

The Hikari pool is small (10) with a short connection timeout on purpose. Requests waiting
on a contended wallet are already serialised by row locks; a large pool would convert that
queue into a larger set of threads waiting on the same locks without improving throughput.
Shedding load quickly is the better failure mode, and transient pool exhaustion surfaces as
a retryable `503` rather than a `500`.

## Observability

**Logs** — JSON to stdout via `logstash-logback-encoder`, every line carrying
`correlation_id` and, where known, `user_id`. Domain events are logged explicitly:
`transfer.created`, `transfer.debited`, `transfer.credited`, `transfer.declined`,
`transfer.idempotent_replay`, `transfer.idempotency_conflict`, `wallet.created`,
`wallet.deposited`. Declines and replays log at INFO, not ERROR — they are normal outcomes
of a correct system under load, and logging them as errors would bury real failures during
a burst.

**Metrics** — `/actuator/prometheus`. Request rate, latency (p50/p95/p99 plus histogram
buckets) and error rate come from Actuator; the domain counters are in
[`WalletMetrics`](../src/main/java/com/shiva/wallet/config/WalletMetrics.java).

## Deployment

One container, built multi-stage, running non-root with a `HEALTHCHECK`. Configuration is
entirely environment-driven — `DATABASE_URL` and `PORT` — so the same image runs under
`docker-compose` locally and on Render unchanged. Flyway migrates on startup.

See [OPERATIONS.md](OPERATIONS.md).
