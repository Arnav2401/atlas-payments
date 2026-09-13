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
- **Condition:** present, and `signum() > 0`.
- **Reason code:** *TODO — blocked on the code scheme*
- **Rationale:** a non-positive amount is not a payment. An **absent** amount
  fails R01 rather than R10, because "strictly positive" is unsatisfiable by
  null and deferring it would let R02 run against a null amount and emit a
  second, misleading rejection.
- **Implementation note:** `signum()`, not `compareTo(ZERO)` and never
  `equals(ZERO)` — `0.00` is not `equals` to `0` in `BigDecimal` (same value,
  different scale), so `equals` would have been the bug.
- **Test cases:**
  - valid — `100.00`
  - invalid — `-0.01`
  - edge — `0`, `0.00`, `-0.00`, `0.0000` all rejected identically
  - edge — absent amount rejected, by this rule

**Status:** implemented, 7 tests passing.

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

## R03 — Currency is a supported settlement currency

- **Field:** `instructedCurrency`
- **Phase:** structural
- **Condition:** present, non-blank, and a member of an explicit allow-list of
  the currencies this service settles. Currently AED, AUD, BHD, CAD, CHF, EUR,
  GBP, HKD, INR, JPY, KWD, SGD, USD.
- **Reason code:** *TODO — blocked on the code scheme*
- **Source of truth:** an explicit allow-list — **not** `java.util.Currency`.

  The obvious implementation is `Currency.getAvailableCurrencies()`. It is wrong
  for this rule. That set has 233 entries on JDK 21 and includes `DEM`, `FRF`
  and `ZWD` — currencies withdrawn years ago — plus the ISO 4217 pseudo-codes
  `XXX` ("no currency") and `XAU` (gold), which report a minor unit of `-1` and
  would break R02 downstream. `java.util.Currency` is bundled data describing
  every code ISO has ever assigned, tracking the JDK version rather than any
  live feed, so it cannot express "live".

  A payment service settles the corridors it has arrangements for, which is
  always a small subset of ISO 4217. "Supported" is the honest predicate and it
  implies "live". A test asserts every entry in the allow-list is a real ISO
  code, so a typo fails the build instead of silently rejecting good payments.
- **Case handling:** rejected, not normalised. ISO 4217 codes are uppercase by
  definition. Postel's law argues for `toUpperCase()` and is defensible, but
  normalising at the boundary means every downstream comparison, ledger row and
  reconciliation report must agree on where normalisation happened — in payments
  that ambiguity is how two systems end up disagreeing about whether they hold
  the same currency. The JDK agrees: `Currency.getInstance("usd")` throws.
- **Test cases:**
  - valid — `USD`
  - invalid — `XYZ`
  - edge — `usd`, `Usd`, `uSD` rejected, not normalised
  - edge — `DEM` rejected even though the JDK knows it
  - edge — absent and blank rejected
  - pinned — a test asserts the JDK *does* still list `DEM` and `ZWD`, so if a
    future JDK cleans up its data this justification fails loudly rather than
    going stale

**Status:** implemented, 10 tests passing.

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
