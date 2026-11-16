#!/usr/bin/env python3
"""
Live measurement run for fraud triage on the payments ledger.

Creates fresh accounts, funds them with deposits (SYSTEM source: no Jev call),
then sends ~40 synthetic user-to-user transfers that each trigger ONE real Jev call.
Prints the outcome mix per scenario and client-side end-to-end latency (p50/p95),
and writes every result to a CSV.

Jev's own latency and token usage are stored server-side in transfer_decisions;
run the SQL printed at the end in the Neon SQL editor to get those numbers.

Usage:
    python3 scripts/measure_triage.py                      # against production
    python3 scripts/measure_triage.py http://localhost:8080  # against a local run

Uses only the Python standard library. Requests are sent one at a time so the
latency numbers aren't distorted by concurrency.
"""
import csv
import json
import random
import sys
import time
import urllib.error
import urllib.request
import uuid

BASE = (sys.argv[1] if len(sys.argv) > 1 else "https://payment-ledger-uzmd.onrender.com").rstrip("/")
RUN_ID = time.strftime("%Y%m%d-%H%M%S")
KEY_PREFIX = f"measure-{RUN_ID}"
random.seed(42)  # same amounts every run, so runs are comparable

results = []  # one dict per Jev-triggering transfer


def call(method, path, body=None, headers=None, timeout=90):
    """Returns (http_status, parsed_json_or_text, elapsed_ms)."""
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    start = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            status, raw = resp.status, resp.read().decode()
    except urllib.error.HTTPError as e:
        status, raw = e.code, e.read().decode()
    elapsed_ms = (time.perf_counter() - start) * 1000
    try:
        parsed = json.loads(raw) if raw else None
    except json.JSONDecodeError:
        parsed = raw
    return status, parsed, elapsed_ms


def create_account(name, acct_type):
    status, body, _ = call("POST", "/accounts", {"name": name, "type": acct_type, "currency": "CAD"})
    if status != 201:
        sys.exit(f"Could not create account {name}: HTTP {status} {body}")
    return body["id"]


_key_counter = 0


def next_key():
    global _key_counter
    _key_counter += 1
    return f"{KEY_PREFIX}-{_key_counter:03d}"


def deposit(bank_id, to_id, cents):
    status, body, _ = call("POST", "/transfers",
                           {"fromAccountId": bank_id, "toAccountId": to_id,
                            "amountCents": cents, "description": "measure deposit"},
                           {"Idempotency-Key": next_key()})
    if status != 201:
        sys.exit(f"Deposit failed: HTTP {status} {body}")


def classify(status, body):
    if status == 201:
        return "APPROVED"
    if isinstance(body, dict) and body.get("status") == "PENDING_REVIEW":
        return "HELD"
    if isinstance(body, dict) and body.get("status") == "BLOCKED":
        return "BLOCKED"
    return f"OTHER_{status}"  # e.g. 422 insufficient funds, 400, 500


def transfer(scenario, from_id, to_id, cents):
    key = next_key()
    status, body, ms = call("POST", "/transfers",
                            {"fromAccountId": from_id, "toAccountId": to_id,
                             "amountCents": cents, "description": f"measure {scenario}"},
                            {"Idempotency-Key": key})
    outcome = classify(status, body)
    results.append({"scenario": scenario, "key": key, "amount_cents": cents,
                    "http": status, "outcome": outcome, "client_ms": round(ms)})
    print(f"  {scenario:<14} ${cents / 100:>8.2f}  HTTP {status}  {outcome:<14} {ms:7.0f} ms")


def percentile(values, p):
    """Nearest-rank percentile."""
    if not values:
        return None
    ordered = sorted(values)
    rank = max(1, -(-len(ordered) * p // 100))  # ceil(n*p/100)
    return ordered[int(rank) - 1]


def main():
    print(f"Target: {BASE}\nRun id: {RUN_ID}\n")

    print("Waking the server (free Render instances sleep; first request can take ~60 s)...")
    status, _, ms = call("GET", "/v3/api-docs", timeout=120)
    print(f"  HTTP {status} after {ms / 1000:.1f} s\n")

    print("Creating and funding accounts (deposits skip triage: no Jev calls)...")
    bank = create_account(f"measure-{RUN_ID}-bank", "SYSTEM")

    def funded_user(label, cents):
        uid = create_account(f"measure-{RUN_ID}-{label}", "USER")
        deposit(bank, uid, cents)
        return uid

    def payee(label):
        return create_account(f"measure-{RUN_ID}-{label}", "USER")

    # Scenario 1: repeat payments to the same person (normal behaviour).
    regular_sender = funded_user("regular", 100_000)
    regular_payee = payee("regular-payee")

    # Scenario 2: card-testing style burst of tiny transfers to many new recipients.
    burst_sender = funded_user("burst", 10_000)
    burst_payees = [payee(f"burst-payee-{i}") for i in range(8)]

    # Scenario 3: account-takeover style large transfer from a new account to a new recipient.
    big_senders = [funded_user(f"big-{i}", 500_000) for i in range(5)]
    big_payees = [payee(f"big-payee-{i}") for i in range(5)]

    # Scenario 4: mid-size payment to a new recipient (ambiguous).
    mid_senders = [funded_user(f"mid-{i}", 100_000) for i in range(5)]
    mid_payees = [payee(f"mid-payee-{i}") for i in range(5)]

    # Scenario 5: small payment to a new recipient (like the manual live test).
    small_senders = [funded_user(f"small-{i}", 10_000) for i in range(10)]
    small_payees = [payee(f"small-payee-{i}") for i in range(10)]
    print("  done\n")

    print("Sending synthetic transfers (each one = one real Jev call)...")
    for _ in range(12):
        transfer("regular", regular_sender, regular_payee, random.randint(1_500, 4_000))
    for p in burst_payees:
        transfer("burst", burst_sender, p, random.randint(100, 300))
    for s, p in zip(big_senders, big_payees):
        transfer("big_new", s, p, 250_000)
    for s, p in zip(mid_senders, mid_payees):
        transfer("mid_new", s, p, 40_000)
    for s, p in zip(small_senders, small_payees):
        transfer("small_new", s, p, 2_000)

    # ---- summary ----
    print("\nOutcome mix by scenario:")
    scenarios = list(dict.fromkeys(r["scenario"] for r in results))
    outcomes = sorted({r["outcome"] for r in results})
    print("  " + f"{'scenario':<14}" + "".join(f"{o:>14}" for o in outcomes))
    for s in scenarios:
        row = [r for r in results if r["scenario"] == s]
        print("  " + f"{s:<14}" + "".join(f"{sum(r['outcome'] == o for r in row):>14}" for o in outcomes))

    lat = [r["client_ms"] for r in results]
    print(f"\nEnd-to-end POST /transfers latency, client-side (n={len(lat)}):")
    print(f"  p50 {percentile(lat, 50)} ms   p95 {percentile(lat, 95)} ms   max {max(lat)} ms")
    print("  (includes internet round trip, Jev call and database work; Jev-only latency is in the SQL below)")

    out = f"triage-measure-{RUN_ID}.csv"
    with open(out, "w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=list(results[0].keys()))
        writer.writeheader()
        writer.writerows(results)
    print(f"\nWrote {out}")

    print(f"""
Now run this in the Neon SQL editor for Jev-only latency, tokens and cost:

SELECT count(*)                                                     AS decisions,
       count(jev_latency_ms)                                        AS jev_answered,
       percentile_cont(0.5)  WITHIN GROUP (ORDER BY jev_latency_ms) AS jev_p50_ms,
       percentile_cont(0.95) WITHIN GROUP (ORDER BY jev_latency_ms) AS jev_p95_ms,
       sum(jev_input_tokens)                                        AS input_tokens,
       round(sum(jev_input_tokens) * 0.042 / 1000000, 6)            AS est_cost_usd
FROM transfer_decisions
WHERE idempotency_key LIKE '{KEY_PREFIX}-%' AND outcome <> 'SKIPPED';

SELECT outcome, reason_code, count(*)
FROM transfer_decisions
WHERE idempotency_key LIKE '{KEY_PREFIX}-%'
GROUP BY 1, 2 ORDER BY 1, 2;
""")


if __name__ == "__main__":
    main()
