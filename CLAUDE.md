# Working in this repo

A wallet service with peer-to-peer transfers. Small codebase, but the value is concentrated
in a handful of files — read the map before reading code.

## Start here

**New session picking this up? Read these two first, in order:**

1. **[TRACKER.md](TRACKER.md)** — current status, blockers, and the next tasks. Keep it
   updated as things change.
2. **[HANDOVER.md](HANDOVER.md)** — what has been done and why, plus the findings that cost
   real time. Read it before debugging anything, so you do not rediscover them.

Then, for the code itself:

3. **[docs/CODE-MAP.md](docs/CODE-MAP.md)** — every source file, what it does and why it
   exists. This is the index; use it to find the right file instead of grepping.
4. **[docs/CONCURRENCY.md](docs/CONCURRENCY.md)** — the transfer transaction step by step.
   Read this before touching anything in `service/` or `repository/`.
5. **[docs/DESIGN-DECISIONS.md](docs/DESIGN-DECISIONS.md)** — what was chosen, what was
   rejected, and why.
6. **[docs/DATA-MODEL.md](docs/DATA-MODEL.md)** — schema, and which invariant each
   constraint defends.
7. **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)** — layers and request flow.
8. **[docs/OPERATIONS.md](docs/OPERATIONS.md)** — run, deploy, observe, troubleshoot.

## Before you push anything

This repo has **no git remote, deliberately**. It was once pushed to Shiva's office GitHub
account by mistake. **Never create a remote or push without asking him first** — plan
approval is not consent for a push. Background in [HANDOVER.md](HANDOVER.md#the-github-situation--read-before-touching-any-remote).

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
