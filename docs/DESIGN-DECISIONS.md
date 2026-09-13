# Design decisions

The one-page write-up the brief asks for. Data model, the simplest-correct mechanism and
what was rejected, where idempotency lives, and the consistency-versus-availability call.

---

## Data model

```
users          id, external_id, bearer_token
wallets        id, user_id UNIQUE, balance_paise CHECK (>= 0)
transfers      id, public_id, idempotency_key, created_by_user,
               from_wallet_id, to_wallet_id, amount_paise, status, request_hash
               UNIQUE (created_by_user, idempotency_key)
ledger_entries id, transfer_id, wallet_id, delta_paise, entry_type
```

Four decisions worth defending:

**Money is `BIGINT` paise everywhere** — in the column, in the Java `long`, and in the JSON.
No decimal type, no floating point, no currency conversion at any boundary.

**The balance column is authoritative; the ledger is an independent check.** Deriving
balances by summing the ledger is the purer model and self-proves conservation, but it
turns every balance read into an aggregate and makes the locking story considerably harder
to defend. Keeping both means `SUM(delta_paise) = 0` over all `TRANSFER` entries verifies
conservation *without reading the balance column at all* — so it catches a bug that a
balance-against-balance comparison would miss. The burst script asserts on both.

**Transfers store the idempotency key; there is no separate idempotency table.** Explained
below.

**`CHECK (balance_paise >= 0)` is on the table, not just in code.** The application already
refuses to overdraw. The constraint means a future bug — a new endpoint, a manual
`UPDATE`, a careless migration — cannot create a negative balance either.

---

## The simplest-correct mechanism

Conservation and no-overdraft come from four things working together, in this order,
inside **one** transaction:

1. **Lock both wallet rows with two explicit `SELECT … FOR UPDATE` statements, lower
   wallet id first.**
2. **Insert the transfer row**, claiming the idempotency key under its UNIQUE constraint.
3. **Conditional debit**: `UPDATE wallets SET balance_paise = balance_paise - :amt
   WHERE id = :from AND balance_paise >= :amt`.
4. **Credit and two ledger rows**, then set the terminal status.

**Conservation** holds because the debit and the credit share a transaction: there is no
instant at which one is visible without the other, and a failure anywhere rolls back both.

**No overdraft** holds because the balance predicate is in the `WHERE` clause. If the
wallet cannot cover the amount, the `UPDATE` matches zero rows — and a zero-row `UPDATE` is
*not an error in Postgres*, so the transaction is still alive. That is the detail that
makes this design worth its complexity: the decline can be **recorded and committed**
rather than vanishing in a rollback. A declined transfer is therefore durable, addressable
by id, and replays identically.

**Deadlock avoidance** is the ascending-id lock order. Simultaneous A→B and B→A request the
same two locks in the same sequence, so one waits rather than the pair deadlocking.

### The bug this ordering originally had — worth reading

The first implementation inserted the transfer row *first*, to claim the idempotency key
before doing any work. It passed every test and deadlocked immediately under the burst
script: 59 of 60 concurrent transfers failed with SQLSTATE `40P01`.

The cause is not obvious. **In PostgreSQL, inserting a row that references a parent through
a foreign key takes a `FOR KEY SHARE` lock on that parent row.** The transfer row has
foreign keys to *both* wallets, so inserting it locked both wallet rows — in foreign-key
declaration order, which is request order, not ascending id order. The carefully ordered
`FOR UPDATE` calls that followed were then requesting locks that conflicted with key-share
locks already held in the opposite order by the opposing transfer. Classic inversion,
reintroduced by a constraint rather than by any visible `LOCK` statement.

Taking the wallet locks first fixes it: the insert's key-share locks then fall on rows this
transaction already holds exclusively, and acquire nothing new. After the fix the same
burst runs 60/60 clean, and the equivalent test dropped from ~17s to 0.4s — the time had
been deadlock detection and retry.

Two lessons went back into the repo. The ordering constraint is documented at the point of
the code that depends on it, and `ConservationTest` now asserts on **status codes as well
as balances** — it had stayed green throughout, because conservation holds trivially when
the transactions are rolling back.

### Heavier alternatives, and why they were rejected

| Alternative | Why not |
|---|---|
| **Conditional `UPDATE` alone, no explicit locks** | Genuinely simpler, and it was the first choice. But it forces debit-before-credit ordering, which inverts between A→B and B→A. Reordering the statements by wallet id would mean crediting before knowing whether the debit succeeds — and then a decline could not be committed, because the credit would have to be rolled back with it. The explicit locks buy the ability to record declines durably. |
| **`SERIALIZABLE` isolation** | Correct, and arguably the most obviously correct. It costs an application-level retry loop and produces `40001` serialization failures under exactly the contended burst the brief runs. More machinery and worse behaviour under load than row locks that are already sufficient. |
| **`SELECT … FOR UPDATE` with an application-side balance check** | Equivalent guarantees and slightly more readable, but it replaces a single atomic statement with read-then-write and one more round trip per transfer — and round trips are the scarce resource here, because they are held while locks are. The `WHERE` predicate also survives a future caller that forgets the check. |
| **Optimistic locking (`@Version`)** | Retries under contention rather than waiting, and the burst is all contention on a handful of wallets. It would turn a queue into a storm of retries. |
| **Distributed lock (Redis), queue, or saga** | There is one system of record. Introducing a second one to coordinate the first adds a failure mode and buys nothing. |
| **Event sourcing / ledger-only balances** | Elegant, and the audit trail is already there. Every balance read becomes an aggregate, and the no-overdraft check becomes a read-then-decide against a derived value — strictly harder to make correct under concurrency. |

---

## Where idempotency lives

**In a `UNIQUE (created_by_user, idempotency_key)` constraint on the `transfers` table,
inserted in the same transaction as the debit and credit.**

Same transaction is the whole point. The key and the money commit together or roll back
together; there is no window in which the key is recorded but the money has not moved, or
the reverse. A separate idempotency table or a cache would need either a second transaction
— reintroducing exactly that window — or a distributed transaction to avoid one.

The key is scoped **per caller**, so two users cannot collide on a common string like
`retry-1`.

**Same key, same body** → the original result, with the same status code, so a client
cannot tell a replay from the original. Two paths reach it:

- *Fast path*: a retry arriving after the original committed is found by an indexed read.
- *Race path*: a retry arriving while the original is still in flight **blocks on the unique
  index**, then surfaces as a constraint violation once the original commits. Catching it
  and re-reading yields the same answer.

The blocking is a feature, not a workaround — Postgres serialises the duplicate for free,
with no polling and no application lock.

**Same key, different body** → `409`, never a second debit. A SHA-256 `request_hash` of
`(from, to, amount_paise)` is stored on the transfer; a replay whose hash differs is a
conflict. Storing a hash rather than the body keeps the row small and sidesteps having to
canonicalise JSON.

**Declines are idempotent too.** A transfer declined for insufficient funds is a committed
row with the key on it, so replaying returns the same `422` rather than re-attempting the
debit. That is a deliberate choice: retrying the *same* request should not change its
answer just because money arrived in the meantime. A caller who wants a fresh attempt sends
a fresh key.

An implementation detail that matters: `Transfer.id` is `IDENTITY`-generated, so JPA issues
the `INSERT` immediately rather than deferring to flush. The constraint is therefore hit at
a known point in the transaction, not at some later moment.

---

## Consistency versus availability

**CP. Consistency, explicitly at the cost of availability.**

A single Postgres primary is the only system of record. Every transfer is one transaction
against it. If the database is unreachable, the service **refuses writes** — it does not
accept them optimistically, queue them, or reconcile later.

What that gives up, stated plainly:

- **Writes fail during a database failover or restart.** On Neon's free tier the compute
  also suspends when idle, so the first request after a quiet period pays a cold start.
- **No horizontal write scaling.** Throughput is bounded by one primary, and contended
  wallets serialise on row locks. This design would need partitioning or per-wallet sharding
  to go further.
- **No read replicas.** A replica could serve `GET /wallets/{id}` cheaply, but a balance
  that is seconds stale is a support ticket, and the read volume here does not justify it.

Why that is the right trade for this workload: the failure modes are not symmetric.
Rejecting a transfer that could have succeeded costs a retry — the client already holds an
idempotency key, so retrying is safe and free. Accepting a transfer that should have failed
costs real money and a reconciliation process. When the two error directions are that
unequal, availability is the one to give up.

The service does distinguish the cases in its responses. Transient contention returns
**`503` with `Retry-After`** and a `transient_conflict` code, not a `500` — "you lost a
race, nothing was applied, retry with the same key" is a materially different message from
"the service is broken".

---

## Conscious omissions

Not built, because the brief states they are not graded and each would have cost time
better spent on the invariants:

- **No UI.** The brief is explicit.
- **Spring Security** — a plain servlet filter resolves the bearer token. Fewer
  dependencies, less to explain, identical behaviour for four seeded users.
- **No user registration.** Users are seeded by Flyway.
- **No transfer reversal or refund.** The ledger supports it; the API does not expose it.
- **No rate limiting.** The burst script is meant to hammer the service.
- **No multi-currency.** Paise only, which keeps money a single integer type end to end.
- **No two-phase transfer state machine.** `PENDING` exists only inside the transaction.
  A durable pending state earns its keep when settlement is asynchronous or crosses an
  external rail; here it would be state to reconcile with no counterparty to reconcile
  against.

---

## Cost

**₹0.** Render free web service, Neon free Postgres, GitHub public repo. No card required
on any of them. The trade for free is a cold start after idle on both tiers — which is
worth knowing before running a burst against a service that has been quiet.
