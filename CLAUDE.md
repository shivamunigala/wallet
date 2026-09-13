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
9. **[docs/PERFORMANCE.md](docs/PERFORMANCE.md)** — what deploying taught us: round trips
   as the scarce resource, the connection-acquisition reduction, the 512Mi budget, and the
   measurements to repeat before re-tuning anything.

## End every session by updating the tracker

**This is required, not optional.** [TRACKER.md](TRACKER.md) is only worth reading if it is
current, and it is the first thing the next session sees. Before you finish a session, or
whenever the work reaches a natural pause:

1. Update **[TRACKER.md](TRACKER.md)** — move tasks between statuses, add new blockers,
   remove resolved ones, and change the `Last updated` date. If a decision was made, record
   the decision rather than the discussion.
2. Update **[HANDOVER.md](HANDOVER.md)** only when something happened that a future session
   would otherwise have to rediscover: a non-obvious bug and its cause, an environment
   quirk, a dead end that is not worth trying again, or a change in who decided what.
   Routine progress belongs in the tracker, not here.
3. Commit both with the work they describe, so the status and the code never disagree.

Say what changed in the tracker when you report back, so Shiva can see the status moved
without opening the file.

## Before you push anything

`origin` is **git@github.com:shivamunigala/wallet.git** — Shiva's *personal* account, public.
It is **not** his work account, and keeping those apart is deliberate.

Three repo-local settings hold that separation. Do not change or globalise them:

```
user.email      63115458+shivamunigala@users.noreply.github.com
user.name       Shiva Munigala
core.sshCommand ssh -i ~/.ssh/id_ed25519_shivamunigala -o IdentitiesOnly=yes -o IdentityAgent=none
```

`IdentityAgent=none` is load-bearing: the ssh-agent holds his **work** key and offers it
ahead of the `-i` key, so without it pushes authenticate as the wrong account.
`IdentitiesOnly=yes` alone does not prevent this.

Before any push to a new remote, verify the identity rather than assuming:

```bash
git push --dry-run <remote> main     # fails loudly on the wrong account
```

The repo was once pushed to his office account by mistake, because plan approval was treated
as consent for a push. **It is not.** Ask at the moment of pushing, every time. Background in
[HANDOVER.md](HANDOVER.md#the-github-situation--resolved-but-read-this-before-touching-any-remote).

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
