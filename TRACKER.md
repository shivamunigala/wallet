# Tracker

Living status of the Paytm PML Round 2 assignment. **Update this file as things change** —
it is the first thing a new session should read after [CLAUDE.md](CLAUDE.md).

**Last updated:** 2026-09-13
**Overall:** code and docs complete and verified locally. Deployment not started. Blocked on
a GitHub hosting decision.

> **Keep this file current.** [CLAUDE.md](CLAUDE.md) requires every session to update it
> before finishing. A stale tracker is worse than none — the next session trusts it.

---

## Deliverables the brief asks for

| # | Deliverable | Status | Notes |
|---|---|---|---|
| 1 | Live URL (deployed API) | **Not started** | Blocked — see B1 |
| 2 | Public repo | **Blocked** | See B1. Nothing is hosted right now |
| 3 | Public logs link (or screen recording of a burst) | **Not started** | Needs the deploy first |
| 4 | One-command burst script | **Done** | `./scripts/burst.sh <url>` — 15/15 passing locally |
| 5 | One-page write-up | **Done** | [docs/DESIGN-DECISIONS.md](docs/DESIGN-DECISIONS.md) |
| 5a | — AI directed-vs-decided disclosure | **Not started** | **Shiva writes this himself.** See T4 |
| 5b | — free-tier cost note (₹0) | **Done** | In DESIGN-DECISIONS.md |

## Graded requirements

| Requirement | Status | Evidence |
|---|---|---|
| Conservation under concurrency | **Done** | `ConservationTest`; burst scenario 3 |
| No overdraft | **Done** | `OverdraftTest`; burst scenario 4; `CHECK (balance_paise >= 0)` |
| Exactly-once transfer | **Done** | `IdempotencyTest`; burst scenario 2 |
| Race-free get-or-create | **Done** | `GetOrCreateRaceTest`; burst scenario 1 |
| Dockerfile: multi-stage, non-root, HEALTHCHECK | **Done** | Verified: container reports `healthy`, runs as `uid=1001(wallet)` |
| docker-compose: app + Postgres, one command | **Done** | `docker-compose up --build`, cold start from empty volume verified |
| Deployed to a free host + managed Postgres | **Not started** | Neon database exists and is migrated; Render not set up |
| Structured JSON logs with correlation id | **Done** | `logback-spring.xml`; domain events logged |
| Logs publicly viewable | **Not started** | Comes with the deploy |
| Metrics: rate, p99, errors, domain counters | **Done** | `/actuator/prometheus`; 5 `wallet_*` counters verified |

---

## Blockers

### B1. Where does the repo live? — **needs Shiva's decision**

The brief requires a **public** repo. Right now there is **no remote**; the code exists only
in the local git repo.

What happened: it was pushed to `ShivaZT/wallet` (Shiva's **office** GitHub account), which
he did not want. Mitigations already applied:
- Repo set to **private**
- History **force-pushed away** — it now holds a single placeholder `README.md`
- Local `origin` remote **removed**

Deleting it outright is still pending: the `gh` token lacks the `delete_repo` scope, and
`gh auth refresh -h github.com -s delete_repo` failed with HTTP 500 during a **GitHub
incident** on 2026-09-13 (API Requests degraded). Retry once GitHub is healthy.

**Decision needed:** which account hosts the public repo — a personal GitHub account, or
this one. Nothing downstream can proceed without it. Do **not** push anywhere without
asking him first.

---

## Open tasks, in order

### T1. Resolve the old repo
Once GitHub's API is healthy:
```bash
gh auth refresh -h github.com -s delete_repo   # then:
gh repo delete ShivaZT/wallet --yes
```
Or delete via the web UI: repo → Settings → Danger Zone.

### T2. Create the public repo and push
Only after B1 is decided.
```bash
cd /Users/shiva/Desktop/sourcecodes/wallet
git remote add origin <url>
git push -u origin main
```

### T3. Deploy to Render + Neon
Full runbook: [docs/OPERATIONS.md](docs/OPERATIONS.md#deploy-neon--render-0).

- Neon project already exists, in **eu-central-1**, and V1+V2 migrations are **already
  applied**. The connection string is in Shiva's Neon console.
- **Use the DIRECT endpoint, not `-pooler`** — PgBouncer transaction pooling breaks
  Flyway's advisory lock. This was hit and diagnosed already.
- **Set Render's region to `frankfurt`** to match Neon. Not cosmetic: cross-region latency
  collapses contended throughput, because each transfer makes several round trips while
  holding row locks. `render.yaml` already defaults to frankfurt.
- `DATABASE_URL` goes in the Render dashboard as a secret (`sync: false` in the blueprint).
- **Rotate the Neon password** once the assignment is submitted — the current one was pasted
  into a chat transcript.

Then verify:
```bash
curl -s https://<service>.onrender.com/actuator/health
./scripts/burst.sh https://<service>.onrender.com
```

### T4. AI disclosure — **Shiva writes this, not Claude**
The brief asks where he *directed* the AI versus where he *let it decide*. Deliberately not
drafted by Claude; it is his account to give. For accuracy, the record is in
[HANDOVER.md](HANDOVER.md#who-decided-what).

### T5. Public logs link
Render's dashboard has a log stream. Either share a link or record the stream during a
burst run.

---

## Session log

Newest first. One or two lines each — detail belongs in [HANDOVER.md](HANDOVER.md).

| Date | What moved |
|---|---|
| 2026-09-13 | Added TRACKER.md and HANDOVER.md for cross-session continuity; CLAUDE.md now requires the tracker to be updated at the end of every session. |
| 2026-09-13 | Repo pushed to the office GitHub account by mistake, then made private and its history force-pushed away. Deletion still pending (T1). Local `origin` removed. |
| 2026-09-13 | Wrote all six `docs/` documents, including the graded write-up. |
| 2026-09-13 | Found and fixed the foreign-key lock inversion that deadlocked 59 of 60 concurrent transfers. Burst went to 15/15. |
| 2026-09-13 | Built the service, the Testcontainers suite, Docker/compose, the Render blueprint, and the burst script. |
| 2026-09-12 | Repo created; Spring Boot 2.7.18 / Java 8 skeleton, after moving off the original Play Framework plan. |

---

## Verified working (re-check before submitting)

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 1.8)
export DOCKER_HOST="unix://$HOME/.colima/default/docker.sock"   # Colima only

./mvnw clean verify          # 10/10 tests, real PostgreSQL via Testcontainers
docker-compose up --build -d
./scripts/burst.sh           # 15/15 assertions, exit 0
```

Last run: all green, 2026-09-13.

## Not doing (conscious omissions)

UI, user registration, transfer reversal, rate limiting, multi-currency, Spring Security,
two-phase transfer state machine. Rationale in
[DESIGN-DECISIONS.md](docs/DESIGN-DECISIONS.md#conscious-omissions).
