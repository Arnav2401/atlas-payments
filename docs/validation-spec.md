# Validation specification

Ten rules enforced on inbound payment instructions at `POST /payments`.

Each rule is independently implementable and independently testable. Rule IDs are
stable and map 1:1 to a class in `com.atlas.payments.validation.rules` and to a
test class in the same package under `src/test`.

**Scope note.** These rules are written from first principles for this service.
They are *not* derived from CBPR+ usage guidelines and this document is not a
statement of ISO 20022 or SWIFT compliance. Field names are
ISO-20022-flavoured; the semantics here are ours.

## Phases

Rules run in two phases:

- **Structural** — shape, format, code-set membership. Assumes nothing. All
  structural rules run and every failure is collected.
- **Semantic** — business meaning. Runs only if every structural rule passed.

The reason: R02 judges decimal places against the instructed currency, which is
meaningless if R03 has already rejected that currency as unknown. Running it
anyway emits a second, misleading rejection.

## Rejection response

*TODO: document the envelope once DECISION 1 (200-with-status vs 4xx) is settled,
and the reason-code scheme once chosen. Both are referenced from the README.*

---

## R01 — Amount strictly positive

- **Field:** `instructedAmount`
- **Phase:** structural
- **Condition:** *TODO*
- **Reason code:** *TODO*
- **Rationale:** *TODO*
- **Test cases:**
  - valid — *TODO*
  - invalid — *TODO*
  - edge — exactly zero

---

## R02 — Decimal places match the currency's minor unit

- **Field:** `instructedAmount`, `instructedCurrency`
- **Phase:** semantic — depends on R03
- **Condition:** *TODO*
- **Reason code:** *TODO*
- **Rationale:** *TODO*
- **Test cases:**
  - valid — *TODO*
  - invalid — JPY with two decimal places
  - edge — `10` vs `10.00`: equal values, different `BigDecimal` scale

---

## R03 — Currency is a live ISO 4217 code

- **Field:** `instructedCurrency`
- **Phase:** structural
- **Condition:** *TODO*
- **Reason code:** *TODO*
- **Rationale:** *TODO*
- **Source of truth:** *TODO — `java.util.Currency` bundled data, or a curated
  allow-list? Note the JDK list includes historical codes and tracks the JDK
  version rather than a live feed.*
- **Test cases:**
  - valid — *TODO*
  - invalid — *TODO*
  - edge — lowercase input

---

## R04 — `endToEndId` present, non-empty, bounded

*TODO*

---

## R05 — Agent BICs match ISO 9362

*TODO — note this rule covers two fields. Decide whether that stays one rule
with a `field` discriminator, or splits into two so the rule-to-code mapping
stays 1:1.*

---

## R06 — Debtor and creditor accounts present and distinct

*TODO*

---

## R07 — `chargeBearer` in supported set

*TODO*

---

## R08 — Settlement date within window

*TODO — record your value of N, where it is configured, and which clock and
timezone "not in the past" is evaluated against.*

---

## R09 — Debtor country a valid ISO 3166-1 alpha-2 code

*TODO*

---

## R10 — Field lengths bounded

*TODO — be explicit about what this rule does not cover. By the time it runs,
the body has already been deserialised, so total request size must be bounded at
the container or in a filter, not here.*
