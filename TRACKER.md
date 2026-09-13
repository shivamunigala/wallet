# Tracker

Living status of the Paytm PML Round 2 assignment. **Update this file as things change** —
it is the first thing a new session should read after [CLAUDE.md](CLAUDE.md).

**Last updated:** 2026-09-13
**Overall:** **the service is live and green.** <https://wallet-dm4c.onrender.com> is
deployed on Render against Neon, running `6bd88d9`, and `./scripts/burst.sh` passes
**15/15 three consecutive runs, 60/60 transfers each, zero non-201 responses**. Per-transfer
database time measured down from ~93ms to ~37ms. Memory steady at ~268Mi of 512Mi.
Remaining: the public logs link, and Shiva's AI disclosure.

> **Keep this file current.** [CLAUDE.md](CLAUDE.md) requires every session to update it
> before finishing. A stale tracker is worse than none — the next session trusts it.

---

## Deliverables the brief asks for

| # | Deliverable | Status | Notes |
|---|---|---|---|
| 1 | Live URL (deployed API) | **Done** | <https://wallet-dm4c.onrender.com> — health UP, burst 15/15 |
| 2 | Public repo | **Done** | <https://github.com/shivamunigala/wallet> — personal account, public, 7 commits |
| 3 | Public logs link (or screen recording of a burst) | **Not started** | Deploy is live, so this is unblocked — T5 |
| 4 | One-command burst script | **Done** | `./scripts/burst.sh <url>` — 15/15 locally *and* against the live URL |
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
| Deployed to a free host + managed Postgres | **Done** | Render (frankfurt) + Neon (eu-central-1), live and passing the burst |
| Structured JSON logs with correlation id | **Done** | `logback-spring.xml`; domain events logged |
| Logs publicly viewable | **Not started** | Render log stream exists; needs a shareable link or a recording — T5 |
| Metrics: rate, p99, errors, domain counters | **Done** | `/actuator/prometheus`; 5 `wallet_*` counters verified |

---

## Blockers

None open.

---

## Git remote setup — do not change this

`origin` is **git@github.com:shivamunigala/wallet.git**, Shiva's *personal* account. The
separation from his work account is deliberate and entirely **repo-local** — nothing global
was modified:

| Scope | Setting |
|---|---|
| repo-local | `user.email` = `63115458+shivamunigala@users.noreply.github.com` |
| repo-local | `core.sshCommand` = `ssh -i ~/.ssh/id_ed25519_shivamunigala -o IdentitiesOnly=yes -o IdentityAgent=none` |
| global (untouched) | `user.email` is still the zeotap address |
| global (untouched) | `gh` credential helper still points at the work account `ShivaZT` |

`IdentityAgent=none` is **load-bearing**: the ssh-agent holds the work key and offers it
ahead of the `-i` key, which is what made early push attempts authenticate as `ShivaZT`.
`IdentitiesOnly=yes` alone does not prevent this.

All 7 commits were rewritten to the personal noreply identity before the first push, so the
work email appears nowhere in the public history.

---

## Open tasks, in order

### T1. Delete the stray repo on the work account — **DONE**
`ShivaZT/wallet` has been deleted by Shiva. Nothing on the work account references this
project any more.

Optional tidy-up, low value: the unused SSH key `~/.ssh/id_ed25519_personal_github` and its
`.pub`, generated during the mix-up and never used.

### T3. Deploy to Render + Neon — **DONE**
Live at <https://wallet-dm4c.onrender.com>. Runbook:
[docs/OPERATIONS.md](docs/OPERATIONS.md#deploy-neon--render-0).

Service `wallet` (`srv-daj7n6nqj5pc73ci251g`), frankfurt, autodeploy on commit, against Neon
`eu-central-1` with V1+V2 applied. Three failures were diagnosed and fixed getting here —
all three are written up in [HANDOVER.md](HANDOVER.md#6-the-render-oom-was-jvm-sizing-not-a-leak).

Re-verify with:

```bash
curl -s https://wallet-dm4c.onrender.com/actuator/health
./scripts/burst.sh https://wallet-dm4c.onrender.com
```

**Warm it first.** The free instance sleeps when idle and the first burst against a cold
instance fails on timeouts. Hit `/actuator/health` until it returns 200, then burst.

Still outstanding here:
- Keep using the **DIRECT** Neon endpoint, never `-pooler`.

**Neon password: deliberately not rotated.** It was pasted into a chat transcript, and Shiva
decided on 2026-09-13 that this is acceptable — the database holds only synthetic assignment
data and is free-tier. Recorded as a decision so a later session does not "fix" it and break
the running deploy. Worth revisiting only if this project is ever reused for anything real.

### T4. AI disclosure — **Shiva writes this, not Claude**
The brief asks where he *directed* the AI versus where he *let it decide*. Deliberately not
drafted by Claude; it is his account to give. For accuracy, the record is in
[HANDOVER.md](HANDOVER.md#who-decided-what).

### T5. Public logs link — **Shiva does this**
Last deliverable. Full steps in
[OPERATIONS.md](docs/OPERATIONS.md#logs): Render dashboard → `wallet` → **Logs** → Share →
copy the link. Render's share links expire, so generate it close to submitting.

If sharing is not available on the free plan, record the stream instead — open the Logs tab,
start a screen recording, warm the service, and run the burst so the domain events scroll
past live:

```bash
curl -s https://wallet-dm4c.onrender.com/actuator/health   # wait for UP - free tier sleeps
./scripts/burst.sh https://wallet-dm4c.onrender.com
```

---

## Session log

Newest first. One or two lines each — detail belongs in [HANDOVER.md](HANDOVER.md).

| Date | What moved |
|---|---|
| 2026-09-13 | Wrote [PERFORMANCE.md](docs/PERFORMANCE.md) — the deploy-time findings and the round-trip reduction, with the measurements to repeat before re-tuning. Linked from CLAUDE.md, DESIGN-DECISIONS.md and ARCHITECTURE.md. T1 closed (stray repo deleted); Neon password rotation dropped by decision. |
| 2026-09-13 | **Service is live and green** on `6bd88d9`: 15/15 three runs against <https://wallet-dm4c.onrender.com>, 60/60 transfers, no non-201s. Per-transfer DB time ~93ms → ~37ms. Fixed two further failures after the OOM — Hikari pool exhaustion under the burst (pool 5 → 20, timeout 10s), and pool timeouts being reported as 500 because the same timeout wears three different exception types. Then cut `POST /transfers` from four connection acquisitions to two via `TransferPreflight`; burst now passes with a pool of **2**. |
| 2026-09-13 | Diagnosed the Render deploy failure: **runtime** OOM, not build. `MaxRAMPercentage=70` sized the heap alone at ~358Mi of a 512Mi cap, leaving too little for metaspace; the container was killed mid-Hibernate-bootstrap before Tomcat bound a port. Replaced with explicit per-region limits (~439Mi ceiling). Verified locally in a 512m-capped container: healthy, burst 15/15, peak 255Mi. **Not yet pushed.** |
| 2026-09-13 | **Public repo live** at <https://github.com/shivamunigala/wallet>. All 7 commits rewritten to the personal noreply identity; push isolated to a dedicated SSH key with repo-local config only. |
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
