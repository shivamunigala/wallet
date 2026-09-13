# Working in this repo

A wallet service with peer-to-peer transfers. Small codebase, but the value is concentrated
in a handful of files — read the map before reading code.

## Start here

1. **[docs/CODE-MAP.md](docs/CODE-MAP.md)** — every source file, what it does and why it
   exists. This is the index; use it to find the right file instead of grepping.
2. **[docs/CONCURRENCY.md](docs/CONCURRENCY.md)** — the transfer transaction step by step.
   Read this before touching anything in `service/` or `repository/`.
3. **[docs/DESIGN-DECISIONS.md](docs/DESIGN-DECISIONS.md)** — what was chosen, what was
   rejected, and why.
4. **[docs/DATA-MODEL.md](docs/DATA-MODEL.md)** — schema, and which invariant each
   constraint defends.
5. **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)** — layers and request flow.
6. **[docs/OPERATIONS.md](docs/OPERATIONS.md)** — run, deploy, observe, troubleshoot.

## Build and test

Java 8 only. Pin `JAVA_HOME` first — a newer `javac` cannot target Java 8:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 1.8)
./mvnw clean verify
```

Tests need a Docker daemon (Testcontainers, real PostgreSQL). On Colima also:

```bash
export DOCKER_HOST="unix://$HOME/.colima/default/docker.sock"
```

Full stack, one command: `docker-compose up --build`, then `./scripts/burst.sh`.

## Rules this codebase depends on

These are not style preferences. Breaking one breaks a graded invariant.

- **Money is integer paise everywhere.** `long` in Java, `BIGINT` in Postgres, an integer in
  JSON. Never a `double`, `float`, or `BigDecimal`, and never rupees.

- **Never change a balance through the `Wallet` JPA entity.** Every balance change goes
  through the hand-written SQL in `WalletRepository`, so the no-overdraft predicate and the
  row lock always apply. `Wallet` is a read model.

- **Lock wallet rows in ascending id order, before inserting anything that references
  them.** Postgres takes a `FOR KEY SHARE` lock on parent rows when inserting a child row
  with a foreign key, which silently reorders your locks. This exact bug deadlocked an
  earlier version under load. See
  [CONCURRENCY.md](docs/CONCURRENCY.md#1-locks-first-in-ascending-wallet-id-order).

- **The idempotency key must be written in the same transaction as the money.** Not before,
  not after, not in a separate table or cache.

- **A zero-row conditional `UPDATE` is a decline, not an error.** Do not turn it into an
  exception — the transaction has to stay alive so the decline can be committed.

- **Replay handling stays outside the transaction.** A unique-constraint violation aborts
  the Postgres transaction. `TransferExecutor` (transactional) and `TransferService` (not)
  are separate beans for this reason; merging them would break replay handling silently.

- **Concurrency tests run against real PostgreSQL, never H2.** H2 does not reproduce
  `FOR UPDATE`, `ON CONFLICT`, or the zero-row-`UPDATE` behaviour these invariants rest on.

- **Assert on status codes, not just balances.** Conservation holds trivially when
  transactions roll back, so a test that checks only money will stay green while every
  request is failing. That happened here.

## When adding code

- Give each new class a class-level Javadoc saying what it is responsible for and which
  invariant it protects. Existing classes follow this.
- Add a row to [docs/CODE-MAP.md](docs/CODE-MAP.md) for any new file.
- New domain events go through the existing `noun.verb` log convention
  (`transfer.declined`, `wallet.created`) so the log stream stays greppable.
