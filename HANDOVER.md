# Handover

What has been done so far and why, written for a Claude session picking this up cold.
For *what to do next*, read [TRACKER.md](TRACKER.md). For *how the code works*, read
[docs/CODE-MAP.md](docs/CODE-MAP.md).

---

## The task

Shiva is completing the **Paytm PML Round 2 exercise**: a wallet service with P2P
transfers, graded on correctness under concurrency, a genuine containerised deployment with
logs and metrics, and the quality of the written reasoning. UI and feature breadth are
explicitly *not* graded.

The brief lives at `/Users/shiva/Documents/personal/01-wallet-transfer-exercise.md`. It is
**deliberately not committed** — it is Paytm's document and the repo is meant to be public.

## How this session ran

Shiva initially wanted to lead the design himself, then said he was short on time and asked
Claude to make the design calls and implement. He explicitly deferred the AI
directed-vs-decided disclosure to himself, to be written after he reviews the design. Do
not draft it for him.

### Choices Shiva made
- **Spring Boot 2.7.18 on Java 8.** He first asked for Play Framework; after being told Play
  2.7 and JDK 8 are both EOL and that Play's async model fights a blocking, row-locked
  workload, he switched to Spring Boot but kept Java 8. He was told Boot 2.7 is also
  end-of-life and chose it anyway.
- **PostgreSQL via JPA** for persistence.
- **Render + Neon** for hosting.
- **Testcontainers** concurrency tests against real PostgreSQL.
- Deployment approach: Claude writes the configs and runbook and drives whatever the CLI
  allows; Shiva does the signups.

### Choices Claude made
Everything architectural: the lock ordering, the conditional-debit mechanism, where
idempotency lives, the schema and its constraints, the double-entry ledger, the error
taxonomy and status codes, the class layout, the observability wiring, and the
consistency-versus-availability position. Reasoning for each is in
[docs/DESIGN-DECISIONS.md](docs/DESIGN-DECISIONS.md).

> **Shiva should read DESIGN-DECISIONS.md closely enough to defend it live.** The brief says
> the interviewers will probe the reasoning.

---

## What exists now

Eight commits on `main`, working tree clean, pushed to
**<https://github.com/shivamunigala/wallet>** (personal account, public).

Complete and verified: the service, the four-invariant Testcontainers suite (10 tests), the
Dockerfile and compose stack, the Render blueprint, the burst script (15 assertions), and
six documents under `docs/`.

Not done: the deployment, and everything that depends on it — the live URL and the public
logs link. See [TRACKER.md](TRACKER.md).

> Commit SHAs are deliberately not listed here; they went stale once history was rewritten.
> Run `git log --oneline`.

---

## Findings that cost real time — do not rediscover these

### 1. Foreign keys silently broke the lock ordering *(the important one)*

The first implementation inserted the transfer row **before** taking the wallet locks, to
claim the idempotency key early. Every test passed. The burst script then failed **59 of 60
concurrent transfers** with SQLSTATE `40P01`, deadlock.

Cause: **in PostgreSQL, inserting a row that references a parent through a foreign key takes
a `FOR KEY SHARE` lock on that parent row.** The transfer row has foreign keys to *both*
wallets, so inserting it locked both — in foreign-key declaration order, which is request
order, not the ascending-id order the design relies on. The lock inversion came from a
constraint, not from any visible lock statement.

Fix: take the wallet locks first. The insert's key-share locks then land on rows the
transaction already holds exclusively. Result: 60/60 clean, and `ConservationTest` dropped
from ~17s to 0.4s — that time had been deadlock detection.

This is the strongest material in the write-up. It is documented in
[DESIGN-DECISIONS.md](docs/DESIGN-DECISIONS.md#the-bug-this-ordering-originally-had--worth-reading),
[CONCURRENCY.md](docs/CONCURRENCY.md), and at the point of the code that depends on it.

### 2. The test suite hid that bug

`ConservationTest` asserted only on balances, and **conservation holds trivially when
transactions roll back** — so it stayed green while almost every request was failing. It now
asserts on status codes too. Apply that lesson to any new concurrency test.

### 3. Neon's `-pooler` endpoint breaks Flyway

Flyway guards migrations with a **session-level** advisory lock. Neon's `-pooler` host is
PgBouncer in transaction mode, so the unlock lands on a different backend: the lock leaks,
and every later startup fails with `Unable to release PostgreSQL advisory lock`, then
`Number of retries exceeded while attempting to acquire` forever.

Use the **direct** endpoint (drop `-pooler` from the hostname). To clear a stranded lock:

```bash
psql "$DIRECT_URL" -c "SELECT pg_terminate_backend(pid) FROM pg_locks WHERE locktype='advisory';"
```

This was hit, diagnosed, and cleared. The Neon database currently has V1 and V2 applied
successfully.

### 4. Region matters more than it looks

Running the app locally against Neon in Frankfurt made the burst script time out. Each
transfer makes several database round trips **while holding row locks**, so cross-region
latency collapses contended throughput. Deploy the service in the database's region;
`render.yaml` defaults to `frankfurt`.

### 5. Testcontainers versus a modern Docker engine

Three separate obstacles, all now handled in `pom.xml`:
- Testcontainers' bundled docker-java negotiates Docker API **1.32**; engines from Docker 25
  onward reject it with HTTP 400. Fixed with `-Dapi.version=1.43` in surefire's `argLine`.
  It must be on the fork's command line — docker-java reads it while building its config,
  before surefire applies `systemPropertyVariables`, and Testcontainers' own env-var layer
  does not carry this setting.
- The Ryuk cleanup container mounts the socket path the *client* uses, which under Colima
  lives in `$HOME` and does not exist inside the VM. Fixed with
  `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`.
- `DOCKER_HOST` still has to be exported per-machine:
  `export DOCKER_HOST="unix://$HOME/.colima/default/docker.sock"`.

### 6. Smaller ones
- `@SpringBootTest` disables metrics export; asserting on `/actuator/prometheus` needs
  `@AutoConfigureMetrics`.
- The JDK's `HttpURLConnection` throws `HttpRetryException` instead of surfacing a 401 when
  the request body was streamed. The test client sets `setOutputStreaming(false)`.
- JDK 8u492 rejects the old `UseCGroupMemoryLimitForHeap` flags; `MaxRAMPercentage` is the
  working equivalent.

---

## The GitHub situation — resolved, but read this before touching any remote

The repo now lives at **<https://github.com/shivamunigala/wallet>** — Shiva's *personal*
account, public. Setup details are in [TRACKER.md](TRACKER.md#git-remote-setup--do-not-change-this).

How it got there is worth knowing:

It was first pushed to **`ShivaZT/wallet`**, his **office** account. Claude had treated plan
approval as standing consent for the push. **It was not.** Ask again at the moment of
pushing, every time. That repo was set private and its history force-pushed away; deleting
it outright is still open (TRACKER task T1).

### The ssh-agent trap — cost several wrong turns

Pushes kept authenticating as `ShivaZT` even from a repo configured with a dedicated
personal key and `IdentitiesOnly=yes`. **The ssh-agent holds the work key and offers it
ahead of the `-i` key; `IdentitiesOnly=yes` does not prevent that.** The fix is
`-o IdentityAgent=none` in the repo-local `core.sshCommand`.

Claude misread the symptom first, concluding from `ssh -T` greeting `Hi ShivaZT!` that Shiva
had added the key to the wrong account, and sent him to delete a key that was never there.
**Verify with `ssh -o IdentityAgent=none` before blaming key placement.** A `git push
--dry-run` against the target repo is the definitive check — it exercises write permission
without changing anything.

Commit history was rewritten to the personal noreply identity before the first push, so the
zeotap address appears nowhere public. `git filter-branch` leaves the originals under
`refs/original/` — those were deleted and garbage-collected, which is easy to forget.

---

## Environment quirks on this machine

| | |
|---|---|
| JDK | Zulu 8 is the **only** JDK. Maven defaults to JDK 26, which cannot target Java 8 — always `export JAVA_HOME=$(/usr/libexec/java_home -v 1.8)` |
| Docker | Colima, not Docker Desktop. Start it with `colima start` |
| Compose | The `docker compose` plugin is **not** linked; use standalone `docker-compose` |
| `gh` | Authenticated as `ShivaZT`. Scopes: `gist, read:org, repo` — no `delete_repo` |
| `flyctl` | Not installed. Not needed — Render deploys from the repo |
| `jq`, `python3` | Both present. The burst script uses stdlib Python only, so neither is a dependency |

---

## Credentials

A Neon `DATABASE_URL` was pasted into the chat and used to run migrations. It is **not** in
the repo and must not be. **It should be rotated** in the Neon console once the assignment
is submitted. The demo bearer tokens in `V2__seed_demo_users.sql` are public on purpose —
the brief says auth sophistication is not graded and the service holds no real money.

---

## Who decided what

Recorded for accuracy so Shiva can write his own disclosure (TRACKER.md task T4). This is a
factual record, not the disclosure itself.

| Area | Who |
|---|---|
| Language, framework, Java version | Shiva chose; Claude advised against Play and against EOL versions |
| Database, hosting, test strategy | Shiva chose from options Claude presented |
| Schema, constraints, ledger design | Claude decided |
| Lock ordering and the conditional-debit mechanism | Claude decided |
| Where idempotency lives | Claude decided |
| Error taxonomy and status codes | Claude decided |
| Consistency-versus-availability position | Claude decided |
| Class layout, observability wiring | Claude decided |
| Finding and fixing the FK deadlock | Claude found it by running the burst script and diagnosed it |
| Scope control, repo hosting, the disclosure itself | Shiva |
