# Performance, and what deploying actually taught us

Everything here was found by running the burst script against the **deployed** service, not
locally. That distinction is the point of the document: every one of these problems was
invisible against `docker-compose`, and three of them would have been reported as "the
service is broken" by anyone reading only the status codes.

For the correctness argument see [CONCURRENCY.md](CONCURRENCY.md) and
[DESIGN-DECISIONS.md](DESIGN-DECISIONS.md). Nothing here changes an invariant.

---

## The one thing that governs this service

**A transfer holds row locks while it makes network round trips.** Everything else follows.

While a transfer holds the locks on two wallet rows, every other transfer touching either
wallet waits. So the cost of a round trip is not paid once by one request — it is paid by
every request queued behind it. Latency does not merely slow the service down; it multiplies
contention.

That is why the interesting number is not requests per second. It is **how many sequential
round trips a transfer costs, and how long each one takes.**

---

## Local Postgres lies to you

The burst passed 15/15 locally for days while being badly mis-sized for production, because
`docker-compose` puts Postgres on the same Docker network as the app. A round trip there is
sub-millisecond. Against a managed database one network hop away it is ~10ms — four orders
of magnitude — and the design is built around holding locks across exactly those trips.

Measured on the deployed service, median of 15 serial uncontended calls:

| Endpoint | Database work | Median | Over baseline |
|---|---|---|---|
| `GET /actuator/health` | none | 255 ms | — |
| `GET /wallets/{id}` | one read | ~255 ms | ~0 ms |
| `POST /transfers` | see below | 291 ms | **~37 ms** |

The ~255ms baseline is the client's own latency to Frankfurt and cancels out. **Only the
difference means anything**, which is also how to reproduce this from anywhere: time an
endpoint that does no database work, time `POST /transfers`, subtract.

---

## It is not a region problem

This is the first thing people reach for, and it is wrong here. Render is `frankfurt` and
Neon is `eu-central-1` — the same city, deliberately matched (see
[HANDOVER.md](../HANDOVER.md) finding #4; `render.yaml` pins the region).

They are nonetheless different **providers**. Traffic leaves Render's network for AWS and
Neon's connection proxy adds a hop, so a round trip costs ~10ms rather than the ~0ms a local
container gives. Moving regions buys nothing, because the regions already match.

Moving the database to **Render's own free Postgres** would genuinely remove that hop, since
app and database would share a network. It was not done because Render's free Postgres
expires after 30 days and the assignment needs to stay up for graders. That is the trade,
and it is a deliberate one rather than an oversight.

---

## The optimization: four connection acquisitions became two

### What it was doing

`POST /transfers` ran three pre-checks before any money moved:

1. `walletRepository.findById(from)`
2. `walletRepository.findById(to)`
3. `transferRepository.findByCreatedByUserAndIdempotencyKey(...)`

All three sat on a **non-transactional** path. That is the subtle part: with no surrounding
transaction, Spring gives each repository call its own implicit one. Each therefore borrowed
a pooled connection, made a round trip, and handed the connection back. Add the transactional
execute and a transfer cost **four connection acquisitions and four round trips**.

Under a 60-transfer burst that is 240 acquisitions against the pool.

### What it does now

[`TransferPreflight`](../src/main/java/com/shiva/wallet/service/TransferPreflight.java) runs
all three checks inside **one** `@Transactional(readOnly = true)` method, and fetches both
wallets with a single `findAllById` rather than two `findById` calls.

- four acquisitions → **two**
- four round trips → **three**

It is a separate bean for one reason: a method `TransferService` called on *itself* would
bypass the Spring proxy and the annotation would silently do nothing. This is the same
reason [`TransferExecutor`](../src/main/java/com/shiva/wallet/service/TransferExecutor.java)
is separate, for a different underlying rule.

### Why it cannot weaken an invariant

Because the preflight never was a guarantee, before or after. A wallet can be drained
between the check and the transfer either way. The preflight exists only to turn common
failures into clean `404`s and `403`s.

Every invariant that matters is still enforced inside `TransferExecutor`'s transaction,
under row locks, by the conditional `UPDATE` and the unique constraint on the idempotency
key. Replay resolution still happens **outside** that transaction, because a
unique-constraint violation aborts a Postgres transaction.

### Measured, live, same method before and after

| | Server-side database time per transfer |
|---|---|
| four acquisitions | ~93 ms |
| two acquisitions | **~37 ms** |

The sharper demonstration: the burst passes **15/15 against a deliberately tiny pool of 2**
with the original 3s timeout. At four acquisitions, 60 concurrent transfers against two
connections had no chance.

**Repeat that measurement before growing the pool.** A larger pool does not remove work — it
moves the queue out of Hikari and into Postgres, where the waiters hold locks instead of
waiting for a connection.

---

## Sizing the pool

The first live burst failed **41 of 60** transfers with
`wallet-pool - Connection is not available, request timed out after 3000ms`.

At four acquisitions per transfer and ~93ms of database time each, a pool of 5 gave 12 waves
of queueing plus lock serialization on three hot wallet rows — comfortably past a 3s timeout.
The pool is now 20 with a 10s timeout (`DB_POOL_SIZE`, `DB_CONNECTION_TIMEOUT_MS`), both well
inside Neon's free-tier allowance.

With the round-trip reduction in place the pool is no longer the binding constraint, which is
the right order to have fixed things in: the sizing is now headroom rather than a workaround.

---

## Memory: the 512Mi cap

`-XX:MaxRAMPercentage=70.0` bounds **only the heap**. On a 512Mi instance that claimed
~358Mi for heap and said nothing about metaspace, code cache, thread stacks or JVM native
overhead — and Spring Boot 2.7 with Hibernate wants ~90Mi of metaspace before serving a
request. The platform killed the container mid-startup. The JVM never threw
`OutOfMemoryError`; from its point of view it was growing a heap it had been told it could.

Every region is now bounded explicitly for a ~439Mi ceiling — see the comment in the
[Dockerfile](../Dockerfile), which carries the full budget. Steady state on Render is
~268Mi of 512Mi.

Verifying this **requires the cap**, because the cap is the whole problem:

```bash
docker run --memory=512m --memory-swap=512m ... wallet
```

---

## One pool timeout, three exception types

Worth knowing on its own, because it is how a capacity problem disguises itself as a bug.
A Hikari acquisition timeout surfaces as a different exception depending on when it lands:

| Raised | Exception | Spring family |
|---|---|---|
| transaction already running | `TransientDataAccessException` | transient |
| while opening the transaction | `CannotCreateTransactionException` | `TransactionException` — *not* a `DataAccessException` |
| mid-session | `DataAccessResourceFailureException` | **non**-transient |

All three mean *nothing was applied, retry is safe*, so all three now return a retryable
`503`. The third is the trap: "non-transient" describes the JDBC **resource**, not the
request, so a handler on `TransientDataAccessException` does not catch it.

`ApiExceptionHandler`'s javadoc had claimed pool timeouts were covered long before any of
them actually were. Listing one type and believing you have them all is very easy here.

---

## Load shedding, and why the burst retries

Hundreds of concurrent transfers among three wallets are serialised by row locks. That is
the design working, not a fault — but it means the tail waits a long time for a connection,
and whatever exceeds the acquisition timeout is shed as a retryable `503`.

Measured at 300 concurrent against Render:

| Acquisition timeout | Shed as 503 |
|---|---|
| 10s | ~132 of 300 |
| 30s | ~6 of 300 |
| 30s + client retries with the same key | **0 of 300** |

The burst retries a `503` with the **same idempotency key**, backing off. That is what the
`Retry-After` header asks for, what a real client does, and it exercises exactly-once for
real: if a retry ever double-applied, the conservation assertion on the next line would
catch it. The total is unchanged across the retried burst, which is the evidence.

The timeout is deliberately long, and the counter-argument is worth stating rather than
hiding: **a long acquisition timeout converts fast failure into slow failure**, and holds a
Tomcat thread while it waits. It is set high because the graded burst is finite and bounded.
A service under sustained load should prefer a short timeout and shed early — which is safe
precisely because a `503` here means nothing was applied and the idempotency key makes the
retry exactly-once.

---

## One acquisition per request was hiding in the auth filter

`BearerTokenAuthFilter` resolved the bearer token against the database on **every** request
— so the real cost was never four acquisitions per transfer, it was five, and every read
paid one too. The lookup is now cached; tokens are seeded by migration and immutable, which
is the assumption to re-check if user creation is ever added.

The more interesting failure was what happened when that lookup timed out. **An exception
thrown in a servlet filter never reaches `@RestControllerAdvice`**, which only sees
exceptions raised during controller dispatch. So pool exhaustion there produced a bare `500`
with no correlation id and no JSON body — 24 of them in a 300-request burst — while the
identical failure one layer deeper was correctly reported as a retryable `503`. The filter
now catches it and answers in the same shape as the rest of the API.

---

## The measurement discipline this depends on

Conservation and no-overdraft **passed** while 41 of 60 requests were failing.

They pass trivially when transactions roll back: money that never moves is perfectly
conserved. A concurrency test that asserts only on balances will stay green while the
service fails every request — this happened twice in this project, and the burst script only
caught it the second time because it asserts on **status codes** too.

Any new test here must assert on status codes, not just money.
