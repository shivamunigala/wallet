# Concurrency

How a transfer executes, why the steps are in that order, and what each probe in the brief
actually exercises.

Code: [`TransferExecutor.java`](../src/main/java/com/shiva/wallet/service/TransferExecutor.java)
and [`TransferService.java`](../src/main/java/com/shiva/wallet/service/TransferService.java).

---

## The transfer transaction

```
BEGIN

  1. SELECT ... FROM wallets WHERE id = <lower id>  FOR UPDATE
     SELECT ... FROM wallets WHERE id = <higher id> FOR UPDATE

  2. INSERT INTO transfers (..., idempotency_key, request_hash, status)
     VALUES (..., 'PENDING')                         -- may block / violate UNIQUE

  3. UPDATE wallets
        SET balance_paise = balance_paise - :amount
      WHERE id = :from AND balance_paise >= :amount   -- 0 rows = declined

     if 0 rows:
         transfers.status = 'DECLINED_INSUFFICIENT_FUNDS'
         COMMIT and return                            -- decline is durable

  4. UPDATE wallets SET balance_paise = balance_paise + :amount WHERE id = :to
     INSERT INTO ledger_entries (-amount for :from), (+amount for :to)
     transfers.status = 'COMPLETED'

COMMIT
```

Around that, and deliberately **outside** it:

```
  before: is there already a transfer for (caller, key)?  -> replay, no transaction needed
  after:  did the INSERT violate the UNIQUE constraint?   -> re-read it, replay or 409
```

---

## Why each step is where it is

### 1. Locks first, in ascending wallet id order

The ordering is what prevents deadlock. Two transfers touching the same pair of wallets in
opposite directions request the same two locks in the same sequence, so one waits.

**Locks must come before the insert**, and the reason is genuinely non-obvious: in
PostgreSQL, inserting a row that references a parent row through a foreign key takes a
`FOR KEY SHARE` lock on that parent. The transfer row has foreign keys to *both* wallets,
so inserting it locks both wallet rows — in foreign-key declaration order, which is request
order, not ascending id order.

An earlier version of this code inserted first. It deadlocked under load (`40P01`) while
passing every test. With the locks taken first, the insert's key-share locks fall on rows
this transaction already holds exclusively and acquire nothing new.

> If you add a foreign key from `transfers` or `ledger_entries` to any row that is also
> locked here, check this ordering again.

### 2. Insert claims the idempotency key

The `UNIQUE (created_by_user, idempotency_key)` constraint is hit here. A concurrent
request with the same key blocks on the unique index until this transaction commits, then
fails cleanly and re-reads the winner's row. Postgres does the serialisation; there is no
application lock and no polling.

`Transfer.id` is `IDENTITY`-generated, so JPA issues the `INSERT` immediately rather than
deferring it to flush — the constraint is hit at a known point, not at some later moment.

`PENDING` never survives a commit. Either step 3 or step 4 overwrites it in the same
transaction, and a crash in between rolls the whole thing back.

### 3. The conditional debit, and why a decline can be committed

`UPDATE ... WHERE balance_paise >= :amount` matching zero rows is **not an error in
Postgres**. The transaction is still alive and still committable. That single fact is why
the decline can be recorded rather than rolled back, which in turn is what makes a declined
transfer durable, addressable by id, and idempotent on replay.

With `SERIALIZABLE` isolation or an exception-based check, the natural implementation
aborts the transaction and the decline has to be re-created in a second one — which
reintroduces a window where the idempotency key is not yet recorded.

### 4. Credit and ledger, same transaction

Conservation is exactly this: no instant exists in which the debit is visible without the
matching credit. The two ledger rows are written here too, batched into one round trip.

---

## Replay resolution sits outside the transaction

A unique-constraint violation **aborts the Postgres transaction**. Nothing further can be
read on that connection until it rolls back. So the catch has to happen after the
transaction boundary — which is why `TransferExecutor` (transactional) and
`TransferService` (not) are separate beans rather than two methods on one class. A
self-invocation would bypass the Spring proxy and leave the caller holding an already-dead
transaction.

---

## What each probe exercises

| Probe | Mechanism under test | Assertions |
|---|---|---|
| **Concurrent get-or-create** | `UNIQUE (user_id)` + `INSERT ... ON CONFLICT DO NOTHING`. No application lock at all — the database arbitrates. | One wallet id across every response; one row created. |
| **Idempotent retry storm** | The unique index blocking concurrent duplicates. Exercises the *constraint-violation* path, not the fast lookup. | One transfer id, one status code, exactly one debit and one credit. |
| **Conservation under contention** | Ascending lock order under A→B and B→A simultaneously. | Total unchanged, no negative balance, **and no unexpected status codes**. |
| **Overdraft** | The conditional debit's zero-row path. | `422`, nothing moved, retrievable by id, replays identically. |

Run them: `./scripts/burst.sh <url>`. The same four are covered by the Testcontainers suite
against real Postgres — [`ConservationTest`](../src/test/java/com/shiva/wallet/ConservationTest.java),
[`IdempotencyTest`](../src/test/java/com/shiva/wallet/IdempotencyTest.java),
[`GetOrCreateRaceTest`](../src/test/java/com/shiva/wallet/GetOrCreateRaceTest.java),
[`OverdraftTest`](../src/test/java/com/shiva/wallet/OverdraftTest.java).

Both harnesses use a **barrier**, not just a thread pool: a plain pool lets early tasks
finish before later ones are submitted, which is precisely the interleaving these tests
exist to rule out.

---

## Known limits

- **Throughput is bounded by round trips held under lock.** Each transfer makes several
  round trips to the database while holding both row locks, so latency to the database
  translates directly into contended throughput. Running the app in a different region from
  the database degrades it sharply — the burst script needs `--timeout` raised in that
  configuration. Deploy the service in the database's region.
- **Contended wallets serialise.** By design. Going further would need per-wallet
  partitioning or netting, both of which trade away the simple correctness argument.
- **One primary, no write scaling.** See
  [DESIGN-DECISIONS.md](DESIGN-DECISIONS.md#consistency-versus-availability).
