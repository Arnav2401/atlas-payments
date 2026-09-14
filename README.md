# atlas-payments

[![CI](https://github.com/Arnav2401/atlas-payments/actions/workflows/ci.yml/badge.svg)](https://github.com/Arnav2401/atlas-payments/actions/workflows/ci.yml)

A payment processing and financial-crime detection service.

It accepts payment instructions over an HTTP API, validates them against ten
rules, commits them to a double-entry ledger, publishes them asynchronously via
a transactional outbox, scores each one for fraud with an explainable model, and
exposes the whole thing behind authentication with metrics and load-test numbers.

**Status:** M4 core complete — transactional outbox, KRaft-mode Kafka, an idempotent async decisioning pipeline, and a dead-letter queue, all proven live against a real broker, not just in tests. 139 Java tests, 11 Python tests passing.

## A note on ISO 20022

The JSON payload uses ISO-20022-*flavoured* field names — `debtorAgent`,
`creditorAgent`, `endToEndId`, `instructedAmount`, `instructedCurrency`,
`chargeBearer`.

**This is not ISO 20022.** It is not `pacs.008`, it is not XML, it has not been
validated against any CBPR+ usage guideline, and rejection responses are not
`pacs.002`. The field names are borrowed for domain vocabulary and nothing here
claims specification compliance.

## Architecture

```
        POST /payments  (JSON, ISO-20022-flavoured field names)
                   |
                   v
        ┌──────────────────────┐
        │  Payment API         │  Spring Boot · Java 21
        │  - validation rules  │
        │  - JWT auth + RBAC   │
        └──────────┬───────────┘
                   │  single DB transaction
                   v
        ┌──────────────────────┐
        │  PostgreSQL          │
        │  - accounts          │
        │  - journal entries   │
        │  - postings          │
        │  - outbox            │  <-- written atomically with the ledger
        └──────────┬───────────┘
                   │
           outbox poller
                   │
                   v
              [ Kafka ]  payments.submitted
                   │
                   v
        ┌──────────────────────┐
        │  Fraud Service       │  FastAPI · XGBoost · SHAP
        │  - velocity features │  Redis
        │  - SHAP explanations │
        └──────────┬───────────┘
                   │
                   v
              [ Kafka ]  payments.decisioned  +  DLQ
                   │
                   v
        ┌──────────────────────┐
        │  Decision consumer   │  writes decision back to Postgres
        └──────────┬───────────┘
                   │
                   v
        ┌──────────────────────┐
        │  Ops console         │  React — payment list, score, top features
        └──────────────────────┘

Observability: Prometheus + Grafana across all services
```

## Run

```bash
docker compose up -d postgres redis   # fraud-service needs a trained model first — see below
./gradlew :services:payment-api:bootRun
```

Flyway applies the schema at startup. `ddl-auto` is `validate`, so a drift
between the JPA entities and the migrations fails at boot rather than silently
diverging — it has already caught one real mismatch (`CHAR(3)` vs `VARCHAR(3)`).

The fraud service needs a trained model before it can start at all — the
raw dataset it trains from is not distributed with this repo:

```bash
cd services/fraud-service
uv sync
curl -L -o /tmp/paysim.csv https://huggingface.co/datasets/theman10/paysim/resolve/main/paysim.csv
uv run python -m training.train --data /tmp/paysim.csv    # ~2 minutes, writes models/model.json
uv run uvicorn fraud_service.main:app --port 8001          # or: docker compose up fraud-service
```

> **macOS without Homebrew:** XGBoost's wheel needs `libomp.dylib`, which
> `brew install libomp` normally provides. Without Homebrew, copy one from
> any existing installation (Anaconda ships one) to
> `~/.local/share/uv/python/cpython-3.12.14-macos-aarch64-none/lib/libomp.dylib`
> (or the equivalent path for your uv-managed Python install). The Docker
> image needs no such workaround — `apt-get install libgomp1` there instead.

## The ledger

Three tables — `accounts`, `journal_entries`, `postings` — and **no balance
column anywhere**. A balance is `SUM(postings.amount_minor)` for an account.
Storing a running balance is simpler and faster and is exactly what makes a
ledger wrong under concurrency, because it turns every payment into a
read-modify-write on a contended row.

Money is a signed integer count of the currency's minor unit. Positive is a
debit, negative a credit, so "this entry balances" is the single expression
`SUM(amount_minor) = 0`. Minor units come from the currency, so JPY 100 stores
as `100` and BHD 1.234 as `1234` — the case a hard-coded `× 100` gets wrong.

**Two invariants are enforced by the database, not the application:**

| Invariant | Mechanism |
|---|---|
| Exactly one ledger effect per idempotency key | `UNIQUE (idempotency_key)` |
| Every journal entry balances | `DEFERRABLE INITIALLY DEFERRED` constraint trigger |

The trigger must be deferred because the invariant spans rows: after the first
posting the entry is legitimately unbalanced, and only at `COMMIT` is that an
error. A non-deferred check would reject the first leg of every valid entry.

### Idempotency under concurrency

The insert is not guarded, it is *attempted*. Check-then-insert is a
time-of-check-to-time-of-use bug — under READ COMMITTED neither transaction can
see the other's uncommitted row, so both find the key free and both insert. The
unique constraint picks the winner; the loser's transaction is aborted, so it
starts a fresh one and reads back the winner's entry. Both callers get the same
`paymentId`.

A test calls the writer directly, with the pre-check bypassed, and asserts that
exactly one of eight simultaneous inserts survives and seven are refused by the
database — because the version that goes through the pre-check can pass for the
wrong reason if the first thread happens to commit first.

### Isolation level: READ COMMITTED, deliberately

Higher isolation protects read-modify-write cycles. This ledger has none:
postings are append-only and balances are derived, so no value read during a
write could be stale in a way that produces a wrong result. The one invariant
that needs protecting is held by a unique constraint, which is enforced
regardless of isolation level and across processes.

**This answer has a documented expiry.** Add an available-funds check and the
read-modify-write appears immediately — two concurrent payments each read a
sufficient balance and both commit, overdrawing the account. That is a lost
update and it is reachable under READ COMMITTED. The fix would be SERIALIZABLE
with retry, or `SELECT FOR UPDATE` on the account row, and only then does the
`version` column on `accounts` start doing real work.

### Reconciliation

`GET /ledger/reconciliation` returns 200 when the ledger balances and **500 when
it does not** — an unbalanced ledger means money was created or destroyed, and
should read as broken to every monitor watching it.

It checks two things, which are not the same question: the global
`sum(debits) = sum(credits)`, and whether any individual entry fails to balance.
A global sum can be zero while two entries are wrong in equal and opposite
directions, which is how a broken ledger looks healthy from a distance.

Verified green over 10,000 synthetic payments (13.5s, ~735/s single-threaded —
an early number, not a benchmark; M5 measures properly under k6).

### Available funds and optimistic locking

Accounts are typed `CUSTOMER` or `SETTLEMENT`. A customer deposit is a
liability of the bank, so a funded customer account carries a *negative*
signed balance in this ledger's convention (positive = debit); what they can
spend is the negation of it. `SETTLEMENT` accounts are the bank's own position
and are exempt from the funds check — otherwise the first funding entry ever
made would be refused for overdrawing the bank.

The debtor account is read with `OPTIMISTIC_FORCE_INCREMENT`, not plain
optimistic locking. A plain lock only detects that someone else modified the
row; the danger here is the opposite shape — two payments both read the same
unmodified, sufficient balance and both decide to proceed. Forcing the version
increment makes each reader a writer, so the second to commit conflicts and is
reported as `409 ATLAS-E005`, safely retryable under the same idempotency key.

**The creditor is deliberately read without the lock.** It is written to, never
read-and-decided-upon, so locking it protects nothing and only adds contention
— a payroll run crediting many payments to one popular account would otherwise
serialise for no reason. A test found this the hard way: eight concurrent
first-time payments to one new shared creditor failed on version conflicts
until the lock was scoped to the debtor only.

Optimistic over pessimistic (`SELECT FOR UPDATE`): pessimistic serialises every
payment on an account whether or not there is real contention, and holds the
lock for the transaction's duration. Optimistic costs nothing when conflicts
are rare, which is the normal case for a retail account, and costs a 409 when
they are not. The calculus inverts for a heavily used treasury account, where
`SELECT FOR UPDATE` would be the right call for that account specifically.

### Get-or-create under concurrency

Two different first-time payments to the same new counterparty race exactly
like the idempotency case: both find no existing account and both try to
insert one. `AccountProvisioner` resolves it the same way — attempt the
insert, let the unique constraint decide, treat a conflict as "it exists now".

**This class exists in its current shape because of a bug in an earlier
version of itself.** A convenience method called the transactional insert from
another method on the *same* object — self-invocation, which Spring's
proxy-based `@Transactional` does not intercept, so the annotation was
silently ignored and a failed insert corrupted the caller's own transaction
instead of an isolated one. It surfaced as Hibernate refusing to flush a
transient entity with a null identifier: a confusing symptom for a bug the
codebase's own documentation had already named as a risk elsewhere. The fix
was to make the mistake structurally impossible: the class exposes only the
`@Transactional` method, and every caller — necessarily a different bean —
must catch the conflict itself.

### Known gaps, deliberate

- **Accounts are auto-created on first reference.** A bank does not open an
  account because a stranger sent money to it. The production shape is a rule
  that rejects an unknown creditor account plus an onboarding path.
- **No business-date calendar** — R08 is a window check, not a settlement
  calendar.

## Build

Java 21 is pinned via the Gradle toolchain and provisioned automatically — you do
not need it installed. The Gradle distribution is checksum-pinned in
`gradle/wrapper/gradle-wrapper.properties`.

```bash
./gradlew build
```

> Spring Boot 3.5.x supports Gradle 7.6.4+/8.4+, **not** Gradle 9. The wrapper is
> pinned to 8.14.5 for that reason.

## Validation rules

Ten rules, each an independent class with its own test class and its own
rejection reason code.

| ID | Rule | Phase | Reason code | Condition |
|---|---|---|---|---|
| R01 | Amount strictly positive | structural | `ATLAS-V001` | present, `signum() > 0` |
| R02 | Decimal places match currency minor unit | semantic | `ATLAS-V002` | `scale() <= minor unit`, no stripping |
| R03 | Currency is a supported settlement currency | structural | `ATLAS-V003` | member of an explicit allow-list, not the JDK currency set |
| R04 | `endToEndId` present, non-blank, bounded, log-safe | structural | `ATLAS-V004` | max 35, ISO 20022 basic Latin set |
| R05 | Agent BICs match ISO 9362 | structural | `ATLAS-V005` | 4 alpha + 2 alpha + 2 alnum + optional 3 alnum |
| R06 | Debtor and creditor accounts present and distinct | structural | `ATLAS-V006` | distinct after strip + uppercase |
| R07 | `chargeBearer` in supported set | structural | `ATLAS-V007` | DEBT, CRED, SHAR, SLEV |
| R08 | Settlement date within window | semantic | `ATLAS-V008` | today .. today+30 UTC, both inclusive |
| R09 | Debtor country a valid ISO 3166-1 alpha-2 code | structural | `ATLAS-V009` | `Locale.getISOCountries()`, uppercase |
| R10 | Bounds the fields no other rule bounds | structural | `ATLAS-V010` | accounts ≤ 34; 512-char backstop |

Structural rules run first and all failures are collected. Semantic rules run
only if every structural rule passed, because they assume structural validity —
R02 cannot judge decimal places against a currency that R03 has already rejected.

A request can also fail before any rule runs, if the body is not readable as
JSON. That path is separate and reports differently. *(TBD: document it here.)*

**R10 does not bound request size.** By the time it runs, the body has already
been parsed into memory. Request-size limiting belongs at the container, and is
not yet configured.

## API

```bash
curl -X POST localhost:8080/payments \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: demo-1' \
  -d '{"endToEndId":"E2E-001","instructedAmount":100.00,"instructedCurrency":"USD",
       "debtorAgent":"DEUTDEFF","creditorAgent":"CHASUS33XXX",
       "debtorAccount":"DE89370400440532013000","creditorAccount":"GB29NWBK60161331926819",
       "debtorCountry":"DE","chargeBearer":"SHAR","settlementDate":"2026-09-21"}'
```

`{"endToEndId":"E2E-001","status":"ACCEPTED","paymentId":"44cf3931-..."}`

**Rejections return HTTP 200** with `status: "REJECTED"` and one entry per failed
rule. The dividing line is whether the service produced a decision: a payment
evaluated against ten rules was decisioned, and the decision is the payload. A
body that could not be read produced no decision and is a 400 in a different
shape (`ATLAS-E001`).

> **This choice has a consequence for M5.** Generic tooling counts non-2xx
> responses, so a k6 run will report a 0% error rate while rejecting every
> payment, and a status-code Grafana panel will show a healthy service. The
> decision-outcome counters are therefore not optional — they are the only place
> rejection is visible, and the k6 thresholds must assert on them rather than on
> status codes.

`Idempotency-Key` is a **required** header. Required now rather than
optional-then-required, because adding a required header later is a breaking
change. In M1 it reaches an in-memory map; M2 makes it load-bearing.

## Fraud scoring

A separate service (`services/fraud-service`, FastAPI, Python 3.12) scores
every payment after it posts to the ledger. `payment-api` calls it over HTTP
behind a Resilience4j circuit breaker; killing the fraud service leaves
payments processing on a conservative rule fallback — proven live below, not
just asserted.

### Data and what it forced the design to confront

Trained on PaySim1 (public Hugging Face mirror; see
`services/fraud-service/docs/sources.md` — the raw 6.36M-row CSV is not
committed, same convention as every other large reference file here).
Restricted to `TRANSFER`/`CASH_OUT`, 2,770,409 rows: fraud is measured to
occur in no other transaction type, so including them would let a trivial
rule masquerade as model performance.

**Two real bugs were found and fixed by treating suspiciously good numbers as
a reason to look closer, not as a result to write down:**

- The first temporal split cut by step *value*, not row count. Transaction
  volume across the 743 simulated steps is wildly non-uniform (median
  112 rows/step, ranging from 23,768 to single digits), and the sparse tail
  has a fraud rate ~10x the rest of the series. That put a tiny, atypical,
  fraud-dense sliver into the test set, and PR-AUC came back implausibly
  close to perfect against it. Splitting by row count in chronological order
  fixed both the sizing and the skew.
- A rolling-window feature computed with `groupby().rolling()` silently
  scrambled row alignment (that call's result is ordered by group, not by the
  original row order) — caught by the training/serving consistency test, not
  by inspection. Rewritten with the same label-based assignment already
  proven correct for the fan-in feature.

**Why performance is still high, verified rather than assumed:** 97.8% of
fraudulent transactions drain the debtor's balance to within 1%, versus 0.15%
of legitimate ones — a real, pre-transaction, non-leaky signal, and
consistently the model's largest SHAP contributor by a wide margin. It is
also directly why the Java-side fallback rule (below) is not an arbitrary
guess.

### Features

Twelve, in `fraud_service/feature_spec.py`, the single shared definition
training (pandas, offline) and serving (Redis, online) both implement against
— agreement between the two is asserted by a dedicated consistency test, with
a synthetic sequence built specifically to exercise 24-hour window *expiry*,
not just accumulation.

Origin-side "account history" and "velocity" features are cold-started for
almost every PaySim row — 2,768,630 of 2,770,409 origin accounts transact
exactly once in this dataset. Destination accounts repeat substantially
(509,565 unique, 69% appearing more than once, up to 75 times), which is real
fan-in structure — many one-shot senders into one receiving account is the
textbook shape of a mule account — so two features beyond the brief's literal
list were added because the data justified them:
`dest_prior_txn_count_24h` and `dest_prior_distinct_senders_24h`.

### Results (from `models/metrics.json`, regenerable via `uv run python -m training.train --data <path>`, seed 42)

| Metric | Value |
|---|---|
| PR-AUC | 0.9964 |
| Precision at 80% recall | 0.9983 |
| Cost-minimising threshold | 0.163 |
| At that threshold | precision 0.962, recall 0.998, 4,419 flagged, 10 of 4,260 frauds missed |
| Feature computation latency (real Redis) | p50 0.40ms, p95 0.48ms, p99 0.70ms |

**The cost threshold is a real optimisation, not a picked number** — swept
against the actual dollar amount of every missed fraud plus a $25/review
assumption (see `training/threshold.py`), and checked against both trivial
extremes: flagging nothing would cost $6.70B in missed fraud on the test set;
flagging everything would cost $13.7M in review overhead. The chosen threshold
beats both by construction, not by luck.

### Java-side resilience — proven live, not just tested

`FraudClient` calls the service behind a Resilience4j circuit breaker
(`RestFraudClient`); on repeated failure it falls back to
`ConservativeRuleFallback` — the model's own top finding (balance-drain),
turned into a threshold rule with no ML dependency. Verified with the full
stack actually running:

```
payment (1% of balance) -> MODEL,  probability 0.0004, not flagged
payment (100% of balance) -> MODEL, probability 0.99996, flagged
                              [fraud service killed]
payment (1% of balance) -> FALLBACK_RULES, not flagged   (29ms, connection refused)
payment (95% of balance) -> FALLBACK_RULES, flagged
```

A flagged payment is still `ACCEPTED` with the assessment attached, not
auto-rejected — see DECISION 3 in `PaymentController`'s javadoc for why:
auto-rejecting on a probabilistic score needs a review workflow (M5's
ops-console decision action) that does not exist yet.

**Known scoping limitation, stated plainly:** every payment sent to the model
has `isCashOut=false` — atlas-payments has no cash-withdrawal concept, so it
only ever exercises the TRANSFER half of the population the model was trained
on. The CASH_OUT-specific behaviour the model learned is never exercised by
this system's real traffic.

## The transactional outbox and Kafka

**The dual-write problem:** commit the payment to Postgres, then publish to
Kafka as a second, independent operation, and a crash between the two leaves a
payment in the ledger that nothing downstream ever saw. Money moved and
nobody screened it.

**The fix:** `LedgerWriter.write` writes an `outbox` row in the *same*
transaction as the ledger entry — the event's durability is the ledger
entry's durability, because they are the same commit. `OutboxPoller` is a
separate, `@Scheduled` process that reads undispatched rows, publishes to
Kafka, and marks them dispatched — atomicity where it is needed, asynchrony
where it is wanted.

```
POST /payments → ledger write + outbox row (one transaction)
                        ↓
                 OutboxPoller (polls every 500ms)
                        ↓
              Kafka: payments.submitted  (KRaft, single broker, no ZooKeeper)
                        ↓
       fraud-service's Kafka consumer (same ScoringService the /score
       HTTP endpoint uses — one implementation, two callers)
                        ↓
              Kafka: payments.decisioned  (+ payments.dlq for poison messages)
                        ↓
       PaymentDecisionConsumer → payment_decisions (idempotent on payment_id)
```

**Why this runs alongside, not instead of, M3's synchronous HTTP call
(DECISION 4):** the synchronous path gives the API caller an immediate,
in-response verdict; this pipeline gives a durable, replayable, at-least-once
decisioning trail that survives the fraud service being down for an extended
period. Both call the identical `ScoringService`. One real consequence worth
naming: because Redis velocity state advances between the two calls, **the
two scores for the same payment can genuinely differ** — verified live, not
theorised: a payment scored 1.51e-05 synchronously and 1.94e-05 a few hundred
milliseconds later on the async path, because the account's own prior
transaction had already landed in Redis by the second scoring.

### At-least-once, not exactly-once — and what that forces downstream

Delivery is at-least-once on **both** hops of this pipeline, and it is
inherent to the pattern, not a bug to fix later:

- **Outbox → `payments.submitted`.** Publishing to Kafka and marking a row
  dispatched cannot be one atomic operation — they are two different systems.
  A crash after Kafka acknowledges the send but before the mark commits
  republishes the row on the next poll.
- **Fraud service → `payments.decisioned`.** The same gap, one hop later: a
  crash after publishing a decision but before committing the consumed Kafka
  offset causes a redelivery.

What the outbox pattern *does* fully close is the failure the brief names
specifically — crash between the ledger commit and any publish attempt at
all. That window has no duplicate risk, because nothing was ever sent.
Restart, and the still-durable row gets published for the first time. Losing
an event and duplicating one are different failure modes with different
fixes; this system closes the first outright and makes the second harmless
rather than pretending to close it too.

**Made harmless by:** `PaymentDecisionConsumer` attempts the insert into
`payment_decisions` rather than checking first, and lets `UNIQUE(payment_id)`
decide — the exact idempotency shape M2 uses for the ledger's own submission
key, applied a third time to a third kind of race. This is also the complete
answer to "how do you avoid double-processing on a rebalance": it does not
try to avoid seeing a message twice — a rebalance can always hand the same
message to two consumers — it makes seeing it twice produce one row.

### The failure test

> Kill the process between the ledger commit and the publish, restart, and
> prove the payment still reaches the fraud service.

`OutboxKafkaIntegrationTest.killing_the_process_between_commit_and_publish_does_not_lose_the_payment`
proves it against real PostgreSQL and a real, single-broker, KRaft-mode Kafka
(`org.testcontainers.kafka.KafkaContainer`, wrapping the `apache/kafka` image
directly — no ZooKeeper, no Confluent wrapper). "Kill the process" means: the
ledger transaction commits and the poller is never told about it — assert
directly against Postgres that the row is durable and genuinely unpublished.
"Restart" is a fresh `poller.poll()` call, legitimate because the poller
carries no in-memory state of its own; everything it needs to recover is
already sitting in the row the test just proved survived. A literal `kill -9`
would prove the same property through more infrastructure without testing
anything a JVM crash does differently.

### Dead-letter queue

A poison message — malformed JSON, or a processing exception — is retried
twice (1s apart) then published verbatim to `payments.dlq` (headers carry the
original topic, offset, and failure reason) and the offset is committed, so
one bad message cannot block every message behind it in the partition
forever. **Replay:** fix whatever made it unprocessable, then republish its
value to `payments.decisioned` — no consumer restart needed, since the
consumer is idempotent on `payment_id` and a replay is handled the same way
any other redelivery is. Proven live: a message published directly to
`payments.decisioned` as literal garbage (`{ this is not valid json`) landed
on `payments.dlq` within seconds, and the running `payment-api` process never
stopped serving requests throughout.

### Two real bugs, found by running the real pipeline, not by testing each side alone

Every unit and integration test on both sides passed while the live pipeline
was silently broken. Both were caught only by submitting a real payment
through the real, fully-wired system and checking Postgres for the actual
row — treating green tests as a reason to verify, not a reason to stop.

1. **A schema mismatch that routed every real decision to the DLQ.** The
   Python producer always published a `threshold` field;
   `PaymentDecisionedEvent.java` never declared it. With
   `fail-on-unknown-properties: true` (set deliberately back in M1, for
   exactly this class of mistake), every real message failed to deserialise,
   retried twice, and landed on `payments.dlq` — while every test stayed
   green, because every test built the event in Java and serialised *that*,
   which can never disagree with the record's own fields. Fixed by adding
   the field, and by adding a regression test
   (`deserialises_a_literal_payload_matching_the_python_services_actual_schema`)
   built from a JSON string copied verbatim from the Python source, not
   round-tripped through the Java type at all.
2. **A topic-creation race on the Python side.** The Java side declares its
   three topics as `NewTopic` beans, which Spring Boot's `KafkaAdmin` creates
   at startup before anything else touches the broker. Python had no
   equivalent and relied on Kafka's auto-create-on-first-publish behaviour,
   which is a genuine race — `admin.create_topics` is now called explicitly
   for all three topics before the consumer starts, matching the Java side's
   approach rather than trusting broker timing.

## Metrics

Every number below must be regenerable by a command in this repo. Nothing goes
here that cannot be reproduced on demand.

| Metric | Value | Command |
|---|---|---|
| Model PR-AUC | 0.9964 | `uv run python -m training.train --data <path>` |
| Precision at 80% recall | 0.9983 | `uv run python -m training.train --data <path>` |
| Decision threshold + cost justification | 0.163, see `models/metrics.json` | `uv run python -m training.train --data <path>` |
| Sustained throughput | *TBD (M5)* | |
| p50 / p95 / p99 latency | *TBD (M5)* | |
| Feature computation p99 | 0.70ms | `uv run pytest tests/test_feature_latency.py -s` (needs Redis) |

## Documentation

- [`docs/validation-spec.md`](docs/validation-spec.md) — the ten rules in detail
- [`DESIGN_NOTES.md`](DESIGN_NOTES.md) — decision log
