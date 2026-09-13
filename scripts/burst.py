#!/usr/bin/env python3
"""
Reproduces every invariant the brief says will be probed live, against any deployment.

    ./scripts/burst.sh https://your-service.onrender.com

Python 3 standard library only - no pip install, no jq. urllib rather than requests so
this runs on a clean macOS or Linux box as-is.

Scenarios, matching the brief one for one:

  1. Concurrent get-or-create  - N simultaneous POST /wallets for one user, expect one wallet.
  2. Idempotent retry storm    - K simultaneous identical transfers, expect one debit and
                                 identical responses.
  3. Conservation under load   - many concurrent transfers among a few wallets, including
                                 A->B and B->A at the same instant, expect the total
                                 unchanged and no negative balance.
  4. No overdraft              - a debit larger than the balance is declined cleanly,
                                 stays declined on replay, and moves nothing.

Exits non-zero if any assertion fails, so it can gate a deploy.
"""

import argparse
import json
import sys
import urllib.error
import urllib.request
import uuid
from concurrent.futures import ThreadPoolExecutor
from threading import Barrier

# Seeded by the V2 Flyway migration. Public on purpose: this service holds no real money.
TOKENS = ["token-alice", "token-bob", "token-carol", "token-dave"]

# Seeded by V3. Disposable users that start with no wallet, so the get-or-create race can
# actually be exercised -- see scenario 1.
BURST_TOKENS = ["token-burst-%03d" % n for n in range(1, 201)]

GET_OR_CREATE_CALLERS = 50
RETRY_STORM_SIZE = 30
CONTENDED_TRANSFERS = 300
STARTING_BALANCE_PAISE = 50_000
TRANSFER_AMOUNT_PAISE = 3_000


class Api:
    """Minimal HTTP client. Returns (status, parsed_body) and never raises on 4xx/5xx."""

    def __init__(self, base_url, timeout=60):
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout

    def call(self, method, path, token=None, body=None):
        data = json.dumps(body).encode() if body is not None else None
        request = urllib.request.Request(self.base_url + path, data=data, method=method)
        request.add_header("Content-Type", "application/json")
        if token:
            request.add_header("Authorization", "Bearer " + token)
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                return response.status, _parse(response.read())
        except urllib.error.HTTPError as e:
            return e.code, _parse(e.read())

    def wallet_id(self, token):
        return self.call("POST", "/wallets", token)[1]["id"]

    def balance(self, wallet_id, token):
        return self.call("GET", "/wallets/%d" % wallet_id, token)[1]["balance_paise"]

    def deposit(self, wallet_id, token, amount):
        return self.call("POST", "/wallets/%d/deposit" % wallet_id, token,
                         {"amount_paise": amount})

    def counter(self, name):
        """Reads a single Prometheus counter. Used to prove a race actually ran."""
        request = urllib.request.Request(self.base_url + "/actuator/prometheus")
        with urllib.request.urlopen(request, timeout=self.timeout) as response:
            for line in response.read().decode("utf-8").splitlines():
                if line.startswith(name + "{"):
                    return float(line.rsplit(" ", 1)[1])
        return 0.0

    def transfer(self, token, from_id, to_id, amount, key):
        return self.call("POST", "/transfers", token, {
            "from": from_id, "to": to_id, "amount_paise": amount, "idempotency_key": key})


def _parse(raw):
    try:
        return json.loads(raw.decode())
    except (ValueError, UnicodeDecodeError):
        return {}


def simultaneously(tasks):
    """
    Runs every task at the same instant.

    A plain thread pool lets the first tasks finish before the last are submitted, which
    is exactly the interleaving these scenarios need to rule out. The barrier holds every
    thread until all of them have arrived.
    """
    barrier = Barrier(len(tasks))

    def run(task):
        barrier.wait()
        return task()

    with ThreadPoolExecutor(max_workers=len(tasks)) as pool:
        return list(pool.map(run, tasks))


class Report:
    def __init__(self):
        self.rows = []

    def check(self, scenario, claim, passed, detail=""):
        self.rows.append((scenario, claim, passed, detail))
        print("  %s %s%s" % ("PASS" if passed else "FAIL", claim,
                             ("  (%s)" % detail) if detail else ""))

    @property
    def failed(self):
        return [row for row in self.rows if not row[2]]

    def summarise(self):
        print("\n" + "=" * 72)
        if self.failed:
            print("FAILED: %d of %d assertions" % (len(self.failed), len(self.rows)))
            for scenario, claim, _, detail in self.failed:
                print("  - [%s] %s %s" % (scenario, claim, detail))
        else:
            print("All %d assertions passed." % len(self.rows))
        print("=" * 72)
        return 1 if self.failed else 0


def scenario_get_or_create(api, report):
    """
    The race is only real for a user that has no wallet yet.

    Once a user owns a wallet, every concurrent POST /wallets takes the found path and the
    assertion "they all got the same id" passes without the insert race ever running. That
    is the same trap as a conservation test that stays green while every request fails, so
    this claims an unused user from the V3 pool and then *proves* the race happened: exactly
    one of the concurrent callers may create the wallet, so the wallet-created counter must
    rise by exactly one.
    """
    print("\n[1/4] Concurrent get-or-create: %d simultaneous POST /wallets"
          % GET_OR_CREATE_CALLERS)

    token = None
    for candidate in BURST_TOKENS:
        before = api.counter("wallet_wallets_created_total")
        results = simultaneously([lambda: api.call("POST", "/wallets", candidate)]
                                 * GET_OR_CREATE_CALLERS)
        created = api.counter("wallet_wallets_created_total") - before
        if created >= 1:
            token = candidate
            break
        print("      %s already had a wallet, trying the next one" % candidate)
    else:
        report.check("get-or-create",
                     "an unused burst user was available to race on", False,
                     "all %d V3 burst users already own wallets - reseed or extend the pool"
                     % len(BURST_TOKENS))
        return

    statuses = {status for status, _ in results}
    wallet_ids = {body.get("id") for _, body in results}

    print("      raced on %s" % token)
    report.check("get-or-create", "every caller received one and the same wallet id",
                 len(wallet_ids) == 1, "distinct ids: %s" % sorted(wallet_ids))
    report.check("get-or-create", "every response succeeded",
                 statuses == {200}, "statuses: %s" % sorted(statuses))
    report.check("get-or-create",
                 "the race actually ran, and created exactly one wallet",
                 created == 1,
                 "wallet_wallets_created_total rose by %g across %d concurrent creators"
                 % (created, GET_OR_CREATE_CALLERS))


def scenario_retry_storm(api, report):
    print("\n[2/4] Idempotent retry storm: %d simultaneous identical transfers"
          % RETRY_STORM_SIZE)
    alice, bob = api.wallet_id(TOKENS[0]), api.wallet_id(TOKENS[1])
    api.deposit(alice, TOKENS[0], STARTING_BALANCE_PAISE)

    alice_before = api.balance(alice, TOKENS[0])
    bob_before = api.balance(bob, TOKENS[1])
    key = str(uuid.uuid4())

    results = simultaneously(
        [lambda: api.transfer(TOKENS[0], alice, bob, TRANSFER_AMOUNT_PAISE, key)]
        * RETRY_STORM_SIZE)

    transfer_ids = {body.get("id") for _, body in results}
    statuses = {status for status, _ in results}

    report.check("retry-storm", "all responses describe the same transfer",
                 len(transfer_ids) == 1, "distinct ids: %d" % len(transfer_ids))
    report.check("retry-storm", "all responses share one status code",
                 len(statuses) == 1, "statuses: %s" % sorted(statuses))
    report.check("retry-storm", "source wallet debited exactly once",
                 api.balance(alice, TOKENS[0]) == alice_before - TRANSFER_AMOUNT_PAISE,
                 "expected %d" % (alice_before - TRANSFER_AMOUNT_PAISE))
    report.check("retry-storm", "destination wallet credited exactly once",
                 api.balance(bob, TOKENS[1]) == bob_before + TRANSFER_AMOUNT_PAISE,
                 "expected %d" % (bob_before + TRANSFER_AMOUNT_PAISE))

    # Same key, different body: a conflict, never a second debit.
    conflict_status, conflict_body = api.transfer(
        TOKENS[0], alice, bob, TRANSFER_AMOUNT_PAISE * 2, key)
    report.check("retry-storm", "same key with a different body is rejected with 409",
                 conflict_status == 409, "got %d / %s" % (conflict_status,
                                                          conflict_body.get("code")))


def scenario_conservation(api, report):
    print("\n[3/4] Conservation under contention: %d concurrent transfers, both directions"
          % CONTENDED_TRANSFERS)
    tokens = TOKENS[:3]
    wallets = []
    for token in tokens:
        wallet_id = api.wallet_id(token)
        api.deposit(wallet_id, token, STARTING_BALANCE_PAISE)
        wallets.append(wallet_id)

    balances_before = [api.balance(w, t) for w, t in zip(wallets, tokens)]
    total_before = sum(balances_before)

    def make_task(index):
        source = index % len(tokens)
        # Alternating direction puts A->B and B->A in flight at the same moment, which is
        # what deadlocks an implementation that locks in request order.
        target = ((source + 1) if index % 2 == 0 else (source - 1)) % len(tokens)
        # Deliberately larger than an equal share, so some transfers are declined and the
        # decline path is exercised rather than only the happy path.
        amount = TRANSFER_AMOUNT_PAISE * (1 + index % 7)
        return lambda: api.transfer(tokens[source], wallets[source], wallets[target],
                                    amount, str(uuid.uuid4()))

    results = simultaneously([make_task(i) for i in range(CONTENDED_TRANSFERS)])

    balances_after = [api.balance(w, t) for w, t in zip(wallets, tokens)]
    total_after = sum(balances_after)
    statuses = {}
    for status, _ in results:
        statuses[status] = statuses.get(status, 0) + 1

    print("      responses: %s" % statuses)
    print("      balances:  %s -> %s" % (balances_before, balances_after))

    report.check("conservation", "total balance unchanged by transfers",
                 total_after == total_before,
                 "before %d, after %d" % (total_before, total_after))
    report.check("conservation", "no wallet went negative",
                 all(b >= 0 for b in balances_after), "balances: %s" % balances_after)
    report.check("conservation", "no request failed unexpectedly",
                 all(s in (201, 422) for s in statuses),
                 "statuses seen: %s" % sorted(statuses))


def scenario_no_overdraft(api, report):
    print("\n[4/4] No overdraft: a debit larger than the balance")
    alice, bob = api.wallet_id(TOKENS[0]), api.wallet_id(TOKENS[1])
    alice_before = api.balance(alice, TOKENS[0])
    bob_before = api.balance(bob, TOKENS[1])

    # Deliberately one paise more than the wallet holds, so the outcome does not depend on
    # how much money earlier runs happened to leave behind.
    key = str(uuid.uuid4())
    status, body = api.transfer(TOKENS[0], alice, bob, alice_before + 1, key)

    report.check("no-overdraft", "oversized debit is declined with 422",
                 status == 422, "got %d" % status)
    report.check("no-overdraft", "decline is recorded as a transfer, not an error",
                 body.get("status") == "DECLINED_INSUFFICIENT_FUNDS",
                 "status field: %s" % body.get("status"))
    report.check("no-overdraft", "no money moved",
                 api.balance(alice, TOKENS[0]) == alice_before
                 and api.balance(bob, TOKENS[1]) == bob_before)

    # The decline must be addressable and must replay identically, not retry itself.
    transfer_id = body.get("id")
    fetched_status, fetched = api.call("GET", "/transfers/%s" % transfer_id, TOKENS[0])
    report.check("no-overdraft", "declined transfer is retrievable by id",
                 fetched_status == 200
                 and fetched.get("status") == "DECLINED_INSUFFICIENT_FUNDS")

    replay_status, replay = api.transfer(TOKENS[0], alice, bob, alice_before + 1, key)
    report.check("no-overdraft", "replaying the key returns the same decline",
                 replay_status == 422 and replay.get("id") == transfer_id)


def main():
    parser = argparse.ArgumentParser(description="Wallet invariant burst test")
    parser.add_argument("base_url", help="e.g. http://localhost:8080")
    parser.add_argument("--timeout", type=int, default=60,
                        help="per-request timeout in seconds. Raise it when the service and "
                             "its database sit in different regions, where each transfer "
                             "pays several round trips while holding row locks.")
    args = parser.parse_args()

    api = Api(args.base_url, timeout=args.timeout)
    print("Target: %s" % args.base_url)

    status, _ = api.call("GET", "/actuator/health")
    if status != 200:
        print("Service is not healthy (GET /actuator/health returned %d). Aborting." % status)
        return 2

    report = Report()
    scenario_get_or_create(api, report)
    scenario_retry_storm(api, report)
    scenario_conservation(api, report)
    scenario_no_overdraft(api, report)
    return report.summarise()


if __name__ == "__main__":
    sys.exit(main())
