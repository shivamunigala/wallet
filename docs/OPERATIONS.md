# Operations

Run it locally, deploy it, watch it, and prove it correct.

---

## Run locally

### Everything in one command

```bash
docker-compose up --build
```

App on `http://localhost:8080`, Postgres on `5432`. The app waits for the database's own
health check before starting, so a cold start on a clean machine needs no retries.

```bash
curl -s localhost:8080/actuator/health
./scripts/burst.sh http://localhost:8080
```

### From source

Requires **JDK 8**. If the machine has several JDKs, pin it — a newer `javac` cannot target
Java 8 at all:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 1.8)
```

```bash
./mvnw clean verify     # unit + Testcontainers tests (needs a Docker daemon)
./mvnw spring-boot:run  # needs a Postgres on localhost:5432
```

### Running the tests on Colima

The suite runs real PostgreSQL via Testcontainers. On Docker Desktop or CI it works as-is.
On Colima, point Testcontainers at the socket:

```bash
export DOCKER_HOST="unix://$HOME/.colima/default/docker.sock"
```

Two related settings are already handled in `pom.xml` and do not need exporting:

- `-Dapi.version=1.43` on the test JVM. Testcontainers' bundled docker-java negotiates
  Docker API 1.32, which engines from Docker 25 onward reject outright with an HTTP 400.
  It has to be on the fork's command line — docker-java reads it while building its
  configuration, before surefire applies `systemPropertyVariables`.
- `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`, so the Ryuk cleanup
  container mounts the socket path *the daemon* sees rather than the client's path under
  `$HOME`, which does not exist inside the VM.

---

## Deploy: Neon + Render, ₹0

### 1. Neon — free Postgres

1. Sign up at [neon.tech](https://neon.tech) and create a project. Pick a region, and note
   which one.
2. Copy the connection string.

> **Use the DIRECT endpoint, not the pooled one.** Neon offers a `-pooler` host running
> PgBouncer in transaction mode. Flyway guards migrations with a *session-level* advisory
> lock, and under transaction pooling the unlock lands on a different backend — the lock
> leaks and every subsequent startup fails with `Unable to release PostgreSQL advisory
> lock`, then `Number of retries exceeded while attempting to acquire` forever after.
> Remove `-pooler` from the hostname.
>
> If you have already hit this, clear the stranded lock:
> ```bash
> psql "$DIRECT_URL" -c "SELECT pg_terminate_backend(pid) FROM pg_locks WHERE locktype='advisory';"
> ```

### 2. Render — free web service

1. Push this repo to GitHub (public).
2. At [render.com](https://render.com), **New → Blueprint**, and point it at the repo.
   [`render.yaml`](../render.yaml) is picked up automatically: Docker runtime, free plan,
   health check on `/actuator/health`.
3. Set `DATABASE_URL` in the dashboard to the Neon **direct** URL. It is marked
   `sync: false` in the blueprint precisely so the password is never committed.
4. **Set the Render region to match the Neon region.** This is not cosmetic. Each transfer
   makes several database round trips *while holding row locks*, so cross-region latency
   collapses contended throughput. The blueprint defaults to `frankfurt`, to pair with a
   Neon project in `eu-central-1`.

`DATABASE_URL` arrives as `postgres://user:pass@host/db?sslmode=require`, which JDBC cannot
parse. [`DatabaseUrlEnvironmentPostProcessor`](../src/main/java/com/shiva/wallet/config/DatabaseUrlEnvironmentPostProcessor.java)
translates it before the datasource is built, so no other variables are needed.

### 3. Verify the deployment

```bash
curl -s https://<your-service>.onrender.com/actuator/health
./scripts/burst.sh https://<your-service>.onrender.com
```

Expect every assertion to pass and exit code 0.

> On the free tier the service sleeps when idle. The first request after a quiet spell pays
> a cold start of roughly 30–60 seconds, and Neon's compute suspends too. Hit
> `/actuator/health` once and wait for `200` before running a burst, or the first scenario
> will time out against a service that is merely asleep.

---

## Observe

### Logs

Structured JSON on stdout, one object per line, every line carrying `correlation_id`:

```bash
docker-compose logs -f app                       # local
```

**Sharing the Render log stream publicly** (the brief asks for a public logs link *or* a
recording of them streaming during a burst):

1. Render dashboard → the `wallet` service → **Logs** tab.
2. Top right of the log panel → **Share** → enable the shareable link.
3. Copy the generated URL. It is readable without a Render account; anyone with the link can
   watch the stream live.

Render's share links **expire**, so generate one close to submitting and re-check it before
sending. If the option is unavailable on the free plan, record the stream instead: open the
Logs tab, start a screen recording, and run the burst against the live URL in another window
— the domain events scroll past in real time, which is what the brief is actually asking to
see.

```bash
./scripts/burst.sh https://wallet-dm4c.onrender.com
```

Either way, warm the instance first — the free tier sleeps when idle, and a burst against a
cold instance fails on timeouts:

```bash
curl -s https://wallet-dm4c.onrender.com/actuator/health   # wait for {"status":"UP"}
```

Follow a single request through a burst:

```bash
docker-compose logs app | grep '"correlation_id":"<id>"'
```

Watch the domain events as they happen:

```bash
docker-compose logs -f app | grep -oE 'transfer\.[a-z_]+|wallet\.[a-z_]+'
```

### Metrics

```bash
curl -s localhost:8080/actuator/prometheus | grep '^wallet_'
```

| Metric | Meaning |
|---|---|
| `wallet_transfers_created_total` | Transfers that moved money |
| `wallet_transfers_declined_total{reason="insufficient_funds"}` | Declined, nothing moved |
| `wallet_idempotent_replays_total` | Repeats served from the original record |
| `wallet_idempotency_conflicts_total` | Reused key, different body |
| `wallet_wallets_created_total` | Wallets actually created (not get-or-create hits) |

Request rate, error rate and latency come from Actuator:

```bash
curl -s localhost:8080/actuator/prometheus | grep 'http_server_requests_seconds.*quantile="0.99"'
```

---

## The burst script

```bash
./scripts/burst.sh                                   # localhost:8080
./scripts/burst.sh https://<your-service>.onrender.com
python3 scripts/burst.py <url> --timeout 120         # slow link or cold start
```

Python 3 standard library only — no `pip install`, no `jq`. Exits non-zero if any assertion
fails, so it can gate a deploy. It uses a barrier so requests fire *simultaneously* rather
than merely concurrently; a plain thread pool would let early requests finish before later
ones were submitted, which is exactly the interleaving being tested for.

Four scenarios: concurrent get-or-create, idempotent retry storm, conservation under
contention (both directions at once), and overdraft decline. See
[CONCURRENCY.md](CONCURRENCY.md#what-each-probe-exercises).

---

## Demo credentials

Seeded by [`V2__seed_demo_users.sql`](../src/main/resources/db/migration/V2__seed_demo_users.sql):
`token-alice`, `token-bob`, `token-carol`, `token-dave`.

Public on purpose — the brief states auth sophistication is not graded and the service holds
no real money. Do not copy this pattern anywhere that does.

```bash
BASE=http://localhost:8080

# get-or-create a wallet
curl -s -X POST $BASE/wallets -H "Authorization: Bearer token-alice"

# fund it
curl -s -X POST $BASE/wallets/1/deposit -H "Authorization: Bearer token-alice" \
  -H 'Content-Type: application/json' -d '{"amount_paise": 100000}'

# transfer
curl -s -X POST $BASE/transfers -H "Authorization: Bearer token-alice" \
  -H 'Content-Type: application/json' \
  -d '{"from":1,"to":2,"amount_paise":2500,"idempotency_key":"demo-1"}'

# replay it - same result, no second debit
curl -s -X POST $BASE/transfers -H "Authorization: Bearer token-alice" \
  -H 'Content-Type: application/json' \
  -d '{"from":1,"to":2,"amount_paise":2500,"idempotency_key":"demo-1"}'
```

---

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `Unable to release PostgreSQL advisory lock` | Flyway ran against a PgBouncer-pooled endpoint | Use the direct endpoint; terminate the stranded lock holder (above) |
| `Could not find a valid Docker environment` | Testcontainers cannot see the daemon | `export DOCKER_HOST="unix://$HOME/.colima/default/docker.sock"` |
| `client version 1.32 is too old` | Docker engine ≥ 25 vs Testcontainers' pinned API | Already handled by `-Dapi.version=1.43` in `pom.xml` |
| Burst times out | App and database in different regions, or a cold start | Co-locate them; raise `--timeout`; warm with `/actuator/health` first |
| `503 transient_conflict` | Transient lock contention or pool exhaustion | Retry with the **same** idempotency key — nothing was applied |
| Build fails with `invalid target release: 1.8` | Maven is running on a newer JDK | `export JAVA_HOME=$(/usr/libexec/java_home -v 1.8)` |
