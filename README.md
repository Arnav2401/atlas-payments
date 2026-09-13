# atlas-payments

A payment processing and financial-crime detection service.

It accepts payment instructions over an HTTP API, validates them against ten
rules, commits them to a double-entry ledger, publishes them asynchronously via
a transactional outbox, scores each one for fraud with an explainable model, and
exposes the whole thing behind authentication with metrics and load-test numbers.

**Status:** M1 — payment API and validation. All ten rules implemented (98 tests passing); the validator, controller and reason-code scheme outstanding.

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
| R01 | Amount strictly positive | structural | *TBD* | present, `signum() > 0` |
| R02 | Decimal places match currency minor unit | semantic | *TBD* | `scale() <= minor unit`, no stripping |
| R03 | Currency is a supported settlement currency | structural | *TBD* | member of an explicit allow-list, not the JDK currency set |
| R04 | `endToEndId` present, non-blank, bounded, log-safe | structural | *TBD* | max 35, ISO 20022 basic Latin set |
| R05 | Agent BICs match ISO 9362 | structural | *TBD* | 4 alpha + 2 alpha + 2 alnum + optional 3 alnum |
| R06 | Debtor and creditor accounts present and distinct | structural | *TBD* | distinct after strip + uppercase |
| R07 | `chargeBearer` in supported set | structural | *TBD* | DEBT, CRED, SHAR, SLEV |
| R08 | Settlement date within window | semantic | *TBD* | today .. today+30 UTC, both inclusive |
| R09 | Debtor country a valid ISO 3166-1 alpha-2 code | structural | *TBD* | `Locale.getISOCountries()`, uppercase |
| R10 | Bounds the fields no other rule bounds | structural | *TBD* | accounts ≤ 34; 512-char backstop |

Structural rules run first and all failures are collected. Semantic rules run
only if every structural rule passed, because they assume structural validity —
R02 cannot judge decimal places against a currency that R03 has already rejected.

A request can also fail before any rule runs, if the body is not readable as
JSON. That path is separate and reports differently. *(TBD: document it here.)*

**R10 does not bound request size.** By the time it runs, the body has already
been parsed into memory. Request-size limiting belongs at the container, and is
not yet configured.

## Metrics

Every number below must be regenerable by a command in this repo. Nothing goes
here that cannot be reproduced on demand.

| Metric | Value | Command |
|---|---|---|
| Model PR-AUC | *TBD (M3)* | |
| Precision at fixed recall | *TBD (M3)* | |
| Decision threshold + cost justification | *TBD (M3)* | |
| Sustained throughput | *TBD (M5)* | |
| p50 / p95 / p99 latency | *TBD (M5)* | |
| Feature computation p99 | *TBD (M3)* | |

## Documentation

- [`docs/validation-spec.md`](docs/validation-spec.md) — the ten rules in detail
- [`DESIGN_NOTES.md`](DESIGN_NOTES.md) — decision log
