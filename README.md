# wallet

A wallet service with peer-to-peer transfers.

Built for the Paytm PML Round 2 exercise. The focus is correctness under concurrency,
a genuine containerised deployment, and observability — not UI.

## Status

Skeleton only. The build runs and serves a health check. Domain model and API are not
implemented yet — see [Design](#design).

## Prerequisites

- **JDK 8** (the project is pinned to Java 8 / bytecode 52)
- Maven is bundled via the wrapper (`./mvnw`), so no local Maven install is required

This machine has multiple JDKs and Maven defaults to a newer one, which cannot compile
for Java 8. Pin `JAVA_HOME` before building:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 1.8)
```

## Build and run

```bash
./mvnw clean verify
```

```bash
./mvnw spring-boot:run
```

The service listens on `8080` by default, or on `$PORT` when the host sets one.

```bash
curl -s localhost:8080/actuator/health
```

## Tech

| | |
|---|---|
| Language | Java 8 |
| Framework | Spring Boot 2.7.18 |
| Persistence | PostgreSQL via JPA *(not wired up yet)* |
| Deployment | Render + Neon Postgres *(not wired up yet)* |

## API

Not implemented yet. Planned surface:

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/wallets` | Get or create a wallet for a user |
| `GET` | `/wallets/{id}` | Current balance |
| `POST` | `/transfers` | Move money between wallets |
| `GET` | `/transfers/{id}` | Transfer status |

Money is always integer **paise** — never floats, never rupees as decimals.

## Design

TBD.

- Data model —
- Conservation and no-overdraft mechanism —
- Where idempotency lives —
- Consistency vs availability —
