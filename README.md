# atlas-payments

[![CI](https://github.com/Arnav2401/atlas-payments/actions/workflows/ci.yml/badge.svg)](https://github.com/Arnav2401/atlas-payments/actions/workflows/ci.yml)

A payment processing and financial-crime detection service.

It accepts payment instructions over an HTTP API, validates them against ten
rules, commits them to a double-entry ledger, publishes them asynchronously via
a transactional outbox, scores each one for fraud with an explainable model, and
exposes the whole thing behind authentication with metrics and load-test numbers.

**Status:** complete through M5, with the optional M6 built and measured.
158 Java tests and 18 Python tests passing.

| Module | What it covers |
|---|---|
| M1 | Payment API, ten validation rules |
| M2 | Double-entry ledger, idempotency, optimistic locking |
| M3 | XGBoost + SHAP fraud scoring, circuit breaker with a rule fallback |
| M4 | Transactional outbox, Kafka in KRaft mode, DLQ |
| M5 | JWT auth and RBAC, ops console, Prometheus + Grafana, Docker Compose, k6, secret scanning |
| M6 | Neo4j counterparty graph, Louvain + centrality (optional) |

M6 finds 6 of 6 planted fraud rings, but adding its graph features to the M3
model measured a small PR-AUC *regression*. That number is reported as
measured rather than left out; see [Fraud rings](#fraud-rings--the-counterparty-graph-m6-optional).

## A note on ISO 20022

The JSON payload uses ISO-20022-*flavoured* field names: `debtorAgent`,
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
                   │                    ┌──────────────────────┐
                   │            reads   │  Neo4j (M6, optional) │
                   │        ┌───────────│  - counterparty graph │
                   │        │           │  - Louvain + degree/  │
                   │        │           │    PageRank centrality│
                   │        │           └──────────────────────┘
                   v        │
              [ Kafka ]  payments.decisioned  +  DLQ
                   │
                   v
        ┌──────────────────────┐
        │  Decision consumer   │  writes decision back to Postgres
        └──────────┬───────────┘
                   │
                   v
        ┌──────────────────────┐
        │  Ops console         │  React + TypeScript, JWT auth — payment
        │  (ops-console/)      │  list, fraud score, top SHAP features,
        └──────────────────────┘  review/clear/escalate, fraud rings

Auth: JWT bearer tokens, RBAC (ANALYST / SUPERVISOR), enforced with
@PreAuthorize on the payment API and CORS-restricted on the ops console's
origin. Observability: Prometheus + Grafana across all services. The ops
console never talks to fraud-service or Neo4j directly - GET /rings is
proxied through payment-api so M6 answers to the same auth boundary as
everything else (see RingsController).
```

## Run

### The full stack, one command

```bash
cp .env.example .env
# then edit .env: ATLAS_JWT_SECRET=$(openssl rand -base64 32)
docker compose up -d
```

Brings up all nine services: Postgres, Redis, KRaft-mode Kafka,
fraud-service, payment-api, the ops console, Prometheus, Grafana, and Neo4j
(optional, M6).

| Service | URL |
|---|---|
| Payment API | http://localhost:8080 |
| Ops console | http://localhost:3002 |
| Grafana | http://localhost:3001 (`admin` / `atlas-demo`, or browse anonymously) |
| Prometheus | http://localhost:9090 |
| Neo4j browser | http://localhost:7474 |

This needs `services/fraud-service/models/model.json` to already exist (see
training, below). Without it `fraud-service` fails fast at startup rather
than serving with no model. Neo4j comes up empty; see
[Fraud rings](#fraud-rings--the-counterparty-graph-m6-optional) for loading
the graph.

`ATLAS_JWT_SECRET` has no default anywhere in the app, so `.env` (gitignored;
`.env.example` documents the shape) must set a real one before `payment-api`
will start. Get a token and try it:

```bash
curl -X POST localhost:8080/auth/token -H 'Content-Type: application/json' \
  -d '{"username":"analyst1","password":"analyst-demo-password"}'
```

### Just the payment API, against local infra

```bash
docker compose up -d postgres redis   # fraud-service needs a trained model first — see below
ATLAS_JWT_SECRET=$(openssl rand -base64 32) ./gradlew :services:payment-api:bootRun
```

Flyway applies the schema at startup. `ddl-auto` is `validate`, so a drift
between the JPA entities and the migrations fails at boot rather than silently
diverging — it has already caught one real mismatch (`CHAR(3)` vs `VARCHAR(3)`).

The fraud service needs a trained model before it can start at all, and the
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

### Ops console

`docker compose up -d` already brings this up at `localhost:3002`. To run it
against a payment-api on the host instead:

```bash
cd ops-console
npm install
npm run dev   # localhost:5173, proxies nowhere - it talks to VITE_API_BASE_URL directly
```

Sign in as `analyst1` or `supervisor1` (same demo passwords as above). Try
clearing or escalating a flagged payment as `analyst1`: the buttons are not
hidden by role, so what comes back is a real 403 from `@PreAuthorize` on the
server, not a client-side illusion of access control.

## The ledger

Three tables (`accounts`, `journal_entries`, `postings`) and **no balance
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

**One class per rule, not a chain of `if`s.** Each rule is a `ValidationRule`
with an id, a phase and one `check`, so a rule can be unit-tested against its
own inputs, reordered, or switched off without touching the others — and the
reason code it emits is owned by the class that decides it. A chain couples
every rule to the one before it and makes "which rule rejected this, and why"
a matter of reading control flow. The cost is more files; the benefit is that
`RuleId` is an enum a reviewer can enumerate, and `docs/validation-spec.md`
maps one-to-one onto it.

**Versioning them** is the open question, and the honest answer is that the
thresholds are still constants in the rule classes. R08's window and R10's
bounds belong in `application.yaml` (there is a `TODO` on it) so a rule change
is config, not a recompile — with the rule id and version stamped on the
rejection so a decision stays explainable after the rule moves on.

## API

```bash
TOKEN=$(curl -s -X POST localhost:8080/auth/token \
  -H 'Content-Type: application/json' \
  -d '{"username":"analyst1","password":"analyst-demo-password"}' | jq -r .accessToken)

curl -X POST localhost:8080/payments \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{"endToEndId":"E2E-001","instructedAmount":100.00,"instructedCurrency":"USD",
       "debtorAgent":"DEUTDEFF","creditorAgent":"CHASUS33XXX",
       "debtorAccount":"DE89370400440532013000","creditorAccount":"GB29NWBK60161331926819",
       "debtorCountry":"DE","chargeBearer":"SHAR","settlementDate":"'"$(date -u +%F)"'"}'
```

Settlement date must be today or later (R08), hence the substitution. A debtor
account with no funds comes back `REJECTED` with `ATLAS-L001` — fund it first
via `POST /ops/funding` with a supervisor token.

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

**Gradient boosting, not a neural network.** The data is tabular, mixed-type
and about 2.7M rows — the regime where boosted trees are the stronger baseline
and a net has nothing to exploit: no spatial or sequential structure to learn,
and far less data than one needs to beat trees on tabular features. Two
practical reasons decide it beyond accuracy: `TreeExplainer` gives *exact*
SHAP values for a tree ensemble rather than the sampled approximation a net
requires, and the brief's own bar is a reviewable per-payment explanation;
and XGBoost handles NaN natively as a split direction, which matters because
`orig_prior_txn_count_24h` is genuinely missing for almost every row.

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
auto-rejected: auto-rejecting on a probabilistic score needs a review
workflow behind it (who clears a false positive, and how), and until that
exists a false positive would be unrecoverable rather than inconvenient.

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

**Why a broker rather than the HTTP call the API already makes.** The
synchronous call to `/score` still exists and still scores the payment inline.
The broker buys the things that call cannot: the decision survives the fraud
service being down (the row waits in the outbox instead of being lost), a
second consumer can be added without the payment path knowing, and a poison
message can be parked on a DLQ and replayed. A direct HTTP publish would put
the payment path's durability at the mercy of another service's uptime — which
is the dual-write problem again, one layer out.

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

## Security, observability, and packaging (M5)

### Authentication and authorization

Self-issued JWTs (HMAC-SHA256, Nimbus), two roles — `ANALYST` and
`SUPERVISOR` — enforced with `@PreAuthorize` at the method level, not just
routing. `POST /auth/token` exchanges a username/password for a token;
everything else requires `Authorization: Bearer <token>`.

- Any authenticated role can view payments and request a review.
- Only `SUPERVISOR` can clear or escalate a flagged payment, or fund an
  account via `POST /ops/funding`.
- `atlas.security.jwt-secret` has **no default anywhere** — the app fails to
  start rather than run with a guessable signing key. Compare the DB password
  and demo-user passwords, which do have local-dev defaults, documented at
  each definition in `application.yaml` and `DemoUserStore.java` for why that
  asymmetry is deliberate: one is genuinely security-critical, the others are
  low-entropy values in a container never exposed off `localhost`.
- There is no real user directory — two fixed demo users (`analyst1`,
  `supervisor1`), BCrypt-hashed, documented in `DemoUserStore.java` as a
  deliberate simplification, not an oversight.

Proven live against the real Spring Security filter chain (not a mocked
one) in `PaymentAuthorizationTest`: wrong password → 401, tampered token
signature → 401, token signed with a different key → 401, an `ANALYST`
token against `/ops/funding` → 403, a `SUPERVISOR` token against the same
endpoint → 200.

### Observability

- **Metrics** — Micrometer + `/actuator/prometheus`. `atlas_payments_decisions_total{outcome}`
  and `atlas_payments_fraud_assessments_total{source,flagged}` are custom
  counters, kept separate because a payment can be `ACCEPTED` and flagged by
  the model at the same time. See [DECISION 1](services/payment-api/src/main/java/com/atlas/payments/api/PaymentController.java)
  in `PaymentController` for why these counters — not HTTP status codes — are
  the only place a rejection is actually visible: every decision, accepted or
  rejected, returns HTTP 200.
- **Dashboards** — Grafana, provisioned as code (`observability/grafana/provisioning/`),
  not clicked together by hand. [`observability/grafana/dashboards/atlas-payments.json`](observability/grafana/dashboards/atlas-payments.json)
  covers decision-outcome throughput, the fraud model's MODEL-vs-`FALLBACK_RULES`
  split (the operational tell for "is the circuit breaker open"), the
  resilience4j circuit breaker state, and server-side p50/p95/p99 latency via
  `histogram_quantile()` over the `http_server_requests_seconds` histogram —
  a different measurement point from k6's client-observed numbers in the
  table below, and not expected to match them exactly.
- **Logs** — structured JSON (`logstash-logback-encoder`), correlated by
  `endToEndId` via MDC, set in `PaymentController.submit` and removed in a
  `finally` so a pooled Tomcat thread never leaks one request's correlation
  id onto the next request it serves.

### Packaging

`docker compose up -d` brings up all nine services — Postgres, Redis, Kafka
(KRaft mode), fraud-service, payment-api, the ops console, Prometheus,
Grafana, and Neo4j (M6, optional — added later, but the same one compose
file) — as one command. Three things found only by actually running that
command, not by inspecting the compose file:

- **A Kafka advertised-listener bug.** A single `PLAINTEXT` listener
  advertised as `localhost:9092` works from the host and from inside the
  Kafka container itself (`docker exec`) — the two ways this project's Kafka
  connectivity had been tested through M4 — but silently fails for genuine
  container-to-container traffic, because Kafka's metadata response tells
  every client to reconnect to `localhost:9092`, which from inside another
  container is that container's own network namespace. Fixed with the
  standard two-listener split: `PLAINTEXT` (host access) and `INTERNAL`
  (container-to-container, advertised as `kafka:29092`) — see the extended
  comment on the `kafka` service in `docker-compose.yml`.
- **A Docker layer-caching bug in the fraud-service build.** `uv sync` builds
  and installs the local `fraud_service` package itself, not just its
  dependencies — but the Dockerfile copied `pyproject.toml`/`uv.lock` and ran
  `uv sync` *before* `COPY src/`, so the project's own source did not exist
  yet at install time. The build succeeded (dependencies installed fine); the
  container then failed at startup with `ModuleNotFoundError: No module named
  'fraud_service'`. Fixed by splitting into `uv sync --no-install-project`
  (cached, dependency-only) followed by a second `uv sync` after `COPY src/`
  — see `services/fraud-service/Dockerfile`.
- **No CORS configuration at all.** The payment API had never been called
  from a browser before the ops console existed, so nothing had ever
  exercised the gap: Spring Security's default is to allow no cross-origin
  requests, and the ops console's own `fetch()` calls failed silently in the
  browser (blocked before the JWT check ever ran) the first time it was
  pointed at a real backend. Fixed with an explicit origin allowlist in
  `SecurityConfig.corsConfigurationSource` — `localhost:5173` (Vite dev
  server) and `localhost:3002` (the compose service) by default, not `"*"`,
  since a wildcard origin cannot be combined with credentialed requests
  (the `Authorization` header) under the CORS spec anyway.

### Connection pool sizing, found by running the load test, not by guessing

The first real k6 run against the full docker-compose stack (20 VUs) came
back with `p(99)=30.03s` and a 1.09% error rate — both failing the script's
own thresholds. The cause was in the payment-api logs, not a mystery:

```
HikariPool-1 - Connection is not available, request timed out after 30001ms
(total=10, active=10, idle=0, waiting=18)
```

Spring Boot's default Hikari pool size (10) was never tuned, because nothing
before this load test ever asked for more than 10 concurrent DB-bound
requests at once. `spring.jpa.open-in-view` is already `false` (see the
`jpa` block in `application.yaml`), so a connection is only held for the
ledger write and outbox insert inside `PaymentStore.record`'s own
`@Transactional` method — not across the synchronous fraud-service HTTP call
that follows it — which rules out a connection leak; this was purely "20
concurrent requests offered against a 10-connection ceiling." Set
`spring.datasource.hikari.maximum-pool-size: 20` — a number taken directly
from this test's own VU count, not a guess — and the same scenario re-run
clean: 0% errors, `p(95)=267ms`, `p(99)=330ms`. Both numbers are in the table
below and in `application.yaml`'s own comment on the setting.

## Fraud rings — the counterparty graph (M6, optional)

Per-transaction scoring (M3) is structurally blind to coordinated fraud: a
model looking at one payment at a time cannot see that a dozen accounts are
funnelling into one mule account. M6 builds a Neo4j graph of the same
training data, runs Louvain community detection and degree/PageRank
centrality over it, and answers two separately measured questions rather
than one vague one — "does this find planted rings" and "does it make the
real fraud model better" are different claims, and conflating them would
have hidden the second one's honest, negative answer behind the first one's
real, positive one.

Optional and off by default: nothing in M1-M5 depends on Neo4j. Bring it up
with `docker compose up -d neo4j` (already part of the full `docker compose
up -d`), then:

```bash
cd services/fraud-service
uv run python -m graph.build_graph --data /path/to/paysim.csv   # ~80s, 2.1M edges
uv run python -m graph.plant_rings                               # 6 synthetic rings, ~instant
uv run python -m graph.detect_rings                               # Louvain + degree centrality, ~15s
uv run python -m training.train_graph_uplift --data /path/to/paysim.csv   # ~5 min, 2 model fits
```

### Ring detection: 0% recall, then 100%, and why the first number is the interesting part

`graph/plant_rings.py` seeds 6 synthetic fan-in rings (8 to 22 source
accounts each, all funnelling into one mule account within a coordinated
few-hour window) — planted rather than found, because PaySim's own fraud
mechanism is a single-hop drain-and-cash-out pattern with no naturally
occurring, *labelled* multi-account ring to detect. `graph/detect_rings.py` then
ranks every account by `in_degree / (temporal_spread_hours + 1)` — high
fan-in concentrated into a short window.

The first real run scored **0 of 6** planted mules in the top 15. Not a bug: an
ordinary account that happened to receive from 4-7 distinct senders within
the same PaySim simulation step (common — median volume is 112 rows/step)
scored higher than every planted ring, because a small in-degree only needs
a small, easily coincidental temporal spread to look "concentrated" under a
pure ratio. Measured directly against the graph: 96,638 real accounts clear
an in-degree of 8, with a *mean* temporal spread of 252 hours — so once
accounts too small to be a meaningful ring candidate are excluded first
(`MIN_SUSPICIOUS_IN_DEGREE = min(RING_SIZES) = 8`, not a tuned constant —
see `graph/detect_rings.py`), the same ranking scores **6 of 6 planted mules
in the top 8** (recall 100%, precision@15 40% against a background of
304,868 real repeat destinations):

```
RING5-MULE   community 2377111   in-degree 22   spread 6h   score 3.14   PLANTED
RING4-MULE   community 2377088   in-degree 18   spread 6h   score 2.57   PLANTED
RING3-MULE   community 2377069   in-degree 15   spread 6h   score 2.14   PLANTED
C474170199   community 1354346   in-degree  9   spread 4h   score 1.80
RING2-MULE   community 2377053   in-degree 12   spread 6h   score 1.71   PLANTED
C1795041102  community  244659   in-degree  9   spread 5h   score 1.50
RING1-MULE   community 2377040   in-degree 10   spread 6h   score 1.43   PLANTED
RING0-MULE   community 2377029   in-degree  8   spread 5h   score 1.33   PLANTED
```

The ops console's "Fraud rings" tab (`GET /rings`, proxied through
payment-api, so the browser never talks to fraud-service or Neo4j directly
and the view answers to the same auth boundary as everything else) renders
this same ranking live, with a
`PLANTED` badge on the six accounts that are ground truth rather than a real
finding.

**A fourth bug, found wiring that tab up for real:** `RingsClient` first
reused `RestFraudClient`'s shared `RestClient` bean, whose 800ms read
timeout is tuned tightly for `/score` — a per-payment call on the critical
path. `GET /rings` is a heavier one-off Cypher aggregation, and it missed
that window in practice (`SocketTimeoutException: Read timed out` in
payment-api's own logs, the first time the ops console's tab was actually
opened against a live graph, not assumed from reading the two call shapes
side by side). Fixed with its own `RestClient` bean and a 5s timeout
(`RingsClientConfig`) — same host, a budget that matches what the call
actually does.

### Graph features fed back into the M3 model: a measured regression, reported as one

`training/train_graph_uplift.py` joins three account-level graph features
(`dest_graph_in_degree`, `dest_pagerank`, `dest_community_size` — computed
from the training-period graph only, the same causality boundary M3's own
velocity features enforce)
onto the M3 feature set and re-derives the baseline in the same run, so the
comparison is apples-to-apples rather than a diff against a possibly-stale
committed number:

| | Feature count | PR-AUC | Precision @ 80% recall |
|---|---|---|---|
| Baseline (M3) | 12 | 0.9964 | 0.9983 |
| + graph features | 15 | 0.9957 | 0.9986 |
| **Delta** | | **-0.0007** | +0.0003 |

**PR-AUC went down, not up.** Reported as measured rather than tuned away,
because that is the honest answer to the brief's own question ("measure and
report the PR-AUC delta"), not the flattering one. Two things are true at
once and worth stating plainly:

- The graph features are not noise — `dest_pagerank` and
  `dest_community_size` rank 5th and 6th by mean |SHAP| in the augmented
  model, ahead of the existing `dest_prior_txn_count_24h`. They carry real
  signal.
- They still made held-out PR-AUC very slightly worse. The likely reason,
  not just asserted but implied by the coverage numbers in the same run:
  graph feature coverage is 95.5% on train but only 60.6% on test (the
  MIN_DEST_IN_DEGREE-scoped graph does not cover every test-period
  destination), and the baseline is already at 0.9964 PR-AUC — there is
  almost no headroom left, and `dest_prior_distinct_senders_24h`
  (already in the M3 feature set) was already capturing most of the
  windowed fan-in signal a coarser, static graph snapshot adds less on top
  of than it costs in train/test coverage mismatch.

**Done when, per the brief:** a planted ring is detected (yes — 6 of 6,
top 8) — the second half ("PR-AUC improvement measured and stated")
is answered honestly: measured, and it is a regression, not an improvement.
The brief's own words apply directly — "the measured lift is what makes
this module worth two weeks... build it only if you will measure it" — the
measurement is what M6 delivers here, not a claimed lift that did not
happen. `models/graph_uplift_metrics.json` is the exact output of the run
above.

## Metrics

Every number below must be regenerable by a command in this repo. Nothing goes
here that cannot be reproduced on demand.

| Metric | Value | Command |
|---|---|---|
| Model PR-AUC | 0.9964 | `uv run python -m training.train --data <path>` |
| Precision at 80% recall | 0.9983 | `uv run python -m training.train --data <path>` |
| Decision threshold + cost justification | 0.163, see `models/metrics.json` | `uv run python -m training.train --data <path>` |
| Sustained throughput | ~98 req/s @ 20 concurrent VUs | `docker run --rm -i --network atlas-payments_default -e BASE_URL=http://payment-api:8080 -v "$(pwd)/load-test:/scripts" grafana/k6 run /scripts/payments-load-test.js` |
| p50 / p95 / p99 latency | 166ms / 267ms / 330ms | (same command) |
| Error rate under load | 0.00% (6,899/6,899 payments decisioned, 100% ACCEPTED) | (same command) |
| Feature computation p99 | 0.70ms | `uv run pytest tests/test_feature_latency.py -s` (needs Redis) |
| M6: planted ring detection | 6/6 recall, 40% precision@15 | `uv run python -m graph.detect_rings` (after `build_graph` + `plant_rings`) |
| M6: graph-feature PR-AUC delta | -0.0007 (regression, reported as measured) | `uv run python -m training.train_graph_uplift --data <path>`, see `models/graph_uplift_metrics.json` |

Throughput is bounded by the synchronous fraud-service round trip
(XGBoost inference + SHAP `TreeExplainer`, one call per payment, no batching)
— not by the ledger write, which is the cheaper half of the request by a wide
margin. See [`load-test/payments-load-test.js`](load-test/payments-load-test.js)
for the scenario (20 VUs ramped over 70s against a pool of 20 pre-funded debtor
accounts, one per VU, so no two VUs contend for the same account row) and
[Connection pool sizing](#connection-pool-sizing-found-by-running-the-load-test-not-by-guessing)
below for a real capacity bug this test found on its first real run.

**Why p50/p95/p99 and not a mean.** Latency here is a long right tail, not a
bell curve — a GC pause, a cold connection, a slow SHAP call — and a mean
averages exactly the requests that hurt into exactly the ones that don't. On
the first load test the mean was 529ms while the median was 42ms: the mean
described no actual request, and both numbers were dominated by 1% of calls
timing out at 30s. Percentiles are also what an SLO can be written against —
"99% under a second" is a commitment you can breach detectably, where "mean
under a second" can hold while a tenth of users are timing out.

## Documentation

- [`docs/validation-spec.md`](docs/validation-spec.md) — the ten rules in detail
- [`DESIGN_NOTES.md`](DESIGN_NOTES.md) — decision log
