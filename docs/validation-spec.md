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

A rejected payment returns **HTTP 200** with `status: "REJECTED"` and one entry
per failed rule. The dividing line is *did the service produce a decision?* — a
payment evaluated against ten rules was decisioned, and the decision is the
payload. A body that could not be read produced no decision, so it is a 400 in a
different shape (see **Pre-rule failures**).

```json
{
  "endToEndId": "E2E-002",
  "status": "REJECTED",
  "rejections": [
    { "code": "ATLAS-V001", "field": "instructedAmount",
      "message": "instructedAmount must be strictly greater than zero" },
    { "code": "ATLAS-V003", "field": "instructedCurrency",
      "message": "instructedCurrency is not a supported settlement currency" }
  ]
}
```

## Reason-code scheme

`ATLAS-Vnnn` — opaque and stable. Chosen over a mnemonic such as
`AMOUNT_NOT_POSITIVE` because a mnemonic describes a rule's *current* meaning,
so when the meaning drifts there is pressure to rename the code — and the code
is a published contract that clients branch on. An opaque code cannot be wrong
when a rule is re-scoped, so it never has to change. The human-readable half
lives in `message`, which is free to change because nobody should parse it.
This is the same trade ISO 20022 makes with `AM02`/`RR02`; the `ATLAS-` prefix
exists so ours are never mistaken for ISO codes.

**The rule:** a published code never changes meaning. If a rule is split, the
old code stays with whichever half keeps the original semantics and the new half
gets a new number. Numbers are never reused.

`ATLAS-E***` is reserved for failures before any rule runs.

## Pre-rule failures

| Code | HTTP | Cause |
|---|---|---|
| `ATLAS-E001` | 400 | Body not readable as a payment instruction — invalid JSON, unparseable date, or an unknown field |
| `ATLAS-E002` | 400 | Required `Idempotency-Key` header absent |
| `ATLAS-E500` | 500 | Narrowing failed after every rule passed — a bug in the rule set, not bad input |

Jackson's own message is discarded rather than returned: it names internal
classes and echoes the offending value, which on this endpoint could be part of
an account identifier.

---

## R01 — Amount strictly positive

- **Field:** `instructedAmount`
- **Phase:** structural
- **Condition:** present, and `signum() > 0`.
- **Reason code:** `ATLAS-V001`
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

## R02 — Decimal places must not exceed the currency's minor unit

- **Field:** `instructedAmount`, `instructedCurrency`
- **Phase:** semantic — depends on R03
- **Condition:** `scale() <= currency.getDefaultFractionDigits()`.
- **Reason code:** `ATLAS-V002`
- **`<=` not `==`:** "100" is a valid USD amount; demanding exactly two decimal
  places would reject it. This also handles negative scale — `1E+2` has scale
  -2 and is a whole number.
- **No `stripTrailingZeros()` first:** `100.000` USD is therefore rejected even
  though its value is representable. The precision of an instruction is part of
  the instruction, and a sender asking for three decimals of USD has a currency
  model that disagrees with ours — better caught at the boundary than silently
  truncated. Consistent with R03 refusing to normalise.
- **Defers rather than double-reporting:** if the amount or currency is unusable
  this rule returns empty and lets R01 or R03 report it.
- **Test cases:** valid `USD 10.50` / `JPY 100` / `BHD 1.234`; invalid
  `JPY 100.50`; edge `10` and `10.00` both accepted for USD; edge `100.000`
  rejected; edge `1E+2` accepted.

**Status:** implemented, 15 tests passing.

---

## R03 — Currency is a supported settlement currency

- **Field:** `instructedCurrency`
- **Phase:** structural
- **Condition:** present, non-blank, and a member of an explicit allow-list of
  the currencies this service settles. Currently AED, AUD, BHD, CAD, CHF, EUR,
  GBP, HKD, INR, JPY, KWD, SGD, USD.
- **Reason code:** `ATLAS-V003`
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

## R04 — `endToEndId` present, non-blank, bounded, safe to log

- **Field:** `endToEndId`
- **Phase:** structural
- **Condition:** present, non-blank, at most **35** characters, and matching the
  ISO 20022 basic Latin set — letters, digits, and `/ - ? : ( ) . , ' +` and space.
- **Max length 35:** from ISO 20022's `EndToEndIdentification35Text`. A number
  with a reason behind it rather than a round one.
- **Why the character set matters:** this field is the correlation key for
  structured logging in M5, so it appears in every log line for the payment.
  Permitting newlines or control characters in a value echoed into logs is log
  injection — a caller could forge log entries. Bounding the set at the boundary
  removes the problem rather than relying on every future log call to escape it.
- **Test cases:** valid `INV-2026-0091/AX`; invalid blank/absent; edge exactly 35
  accepted and 36 rejected; edge `
`, `
`, `NUL` and `<script>` rejected.

**Status:** implemented, 12 tests passing.

---

## R05 — Agent BICs match ISO 9362

- **Field:** `debtorAgent`, `creditorAgent`
- **Phase:** structural
- **Condition:** `^[A-Z]{4}[A-Z]{2}[A-Z0-9]{2}([A-Z0-9]{3})?$` — 4 letters
  (institution) + 2 letters (ISO 3166-1 country) + 2 alphanumeric (location) +
  optional 3 alphanumeric (branch). 8 or 11 characters, never 9 or 10.
- **Stricter than the brief's wording.** "8 or 11 alphanumeric" would accept
  `1234DE5X`. Enforcing the real structure costs nothing and is the difference
  between a length check and a format rule.
- **Known limit:** validates the *shape* of a BIC, not its existence.
  `AAAAGB2L` is well-formed and almost certainly not a real institution.
  Existence checking needs the SWIFT BIC directory, which is licensed data and
  out of scope — the README must not imply otherwise. The country segment is
  also not cross-checked against ISO 3166.
- **One rule, two fields, first failure reported.** Keeps the rule-to-code
  mapping 1:1 and the `field` names which agent. **Cost:** a request with both
  agents malformed takes two round trips to fix, which cuts against this API's
  otherwise collect-everything behaviour. Splitting into two rule IDs is the
  alternative.
- **Test cases:** valid `DEUTDEFF`, `CHASUS33XXX`; invalid 7/9/10/12 characters,
  `1234DE5X`, lowercase; edge the rejection names the offending agent.

**Status:** implemented, 14 tests passing.

---

## R06 — Debtor and creditor accounts present and distinct

- **Field:** `debtorAccount`, `creditorAccount`
- **Phase:** structural
- **Condition:** both present and non-blank, and not equal after stripping
  whitespace and uppercasing.
- **Why distinctness is the substance:** a payment from an account to itself is
  a no-op that would still commit two postings to the M2 ledger and still be
  screened in M3.
- **Comparison normalises; the stored value does not.** This looks like it
  contradicts R03, which refuses to normalise currency. The distinction: R03
  normalising would change the value the system *accepts*; here normalisation
  only decides whether two values are the *same*. Account identifiers are
  case-insensitive in practice, so treating `de89…` and `DE89…` as different
  accounts would let a self-payment through on a trivial disguise. Compare
  loosely, store exactly.
- **Not checked:** IBAN check digits (ISO 7064 mod-97). A genuinely stronger
  rule, but it only applies to IBAN-shaped accounts and this field is not
  constrained to IBANs, so it needs a format discriminator first.

**Status:** implemented, 6 tests passing.

---

## R07 — `chargeBearer` in the supported set

- **Field:** `chargeBearer`
- **Phase:** structural
- **Condition:** exactly one of `DEBT`, `CRED`, `SHAR`, `SLEV`.
- **Reuses the domain enum** rather than declaring a second one in the
  validation package — two enums with the same four constants inevitably drift.
- **Exact match, no normalisation** — consistent with R03 and R09.
- Meanings: DEBT debtor pays all charges, CRED creditor pays, SHAR shared,
  SLEV per the agreed service level.

**Status:** implemented, 10 tests passing.

---

## R08 — Settlement date window

- **Field:** `settlementDate`
- **Phase:** semantic
- **Condition:** present, not before today, not after today + **30** days. Both
  boundaries **inclusive**.
- **The clock is injected.** `LocalDate.now()` inside the rule makes it
  untestable without freezing system time and makes both boundary cases
  impossible to assert reliably. A `Clock` turns "not in the past" into a pure
  function of its inputs.
- **UTC, explicitly.** "Today" is timezone-dependent, so "not in the past"
  without a named zone is ambiguous by construction — the same payment would be
  valid in Mumbai and rejected in New York for eleven and a half hours a day.
  Stated rather than inherited from the server's default zone.
- **Both boundaries inclusive** — stated because "not more than N days forward"
  is exactly the phrasing that produces an off-by-one nobody notices until a
  customer settles on day N.
- **Known limit:** a real settlement system uses a business-date calendar —
  currency holidays, cut-off times, weekends. This is a window check, not a
  calendar, and the README must not imply otherwise.

**Status:** implemented, 7 tests passing.

---

## R09 — Debtor country is ISO 3166-1 alpha-2

- **Field:** `debtorCountry`
- **Phase:** structural
- **Condition:** present and a member of `Locale.getISOCountries()`. Uppercase only.
- **Why the JDK is acceptable here but was not for R03.** Worth being able to
  explain, because it looks inconsistent. R03 needed *live*, and the JDK's
  currency data is a superset including withdrawn currencies — it answered the
  wrong question. Here the rule asks "is this a valid alpha-2 code", which is
  exactly what this list answers. Same bundled-data caveat, much smaller
  consequence: country codes are far more stable than currencies, and this feeds
  screening rather than settlement.
- **Test cases:** valid `IN`, `DE`, `GB`; invalid `ZZ`; edge `UK` rejected and
  `GB` accepted — `UK` is the common abbreviation and is not an ISO code.

**Status:** implemented, 11 tests passing.

---

## R10 — Bound the fields no other rule bounds

- **Field:** `debtorAccount`, `creditorAccount`, plus a total-character backstop
- **Phase:** structural
- **Condition:** each account at most **34** characters (ISO 13616 maximum IBAN
  length); all string fields combined at most **512** characters.
- **Scope, and why it is this narrow.** Implementing this rule surfaced that it
  mostly has nothing to do: every other field is already length-constrained as a
  side effect of its own format rule — R03 pins currency to a three-character
  allow-list, R04 caps `endToEndId` at 35, R05's pattern admits only 8 or 11
  characters, R07 admits four literal values, R09 admits two. Re-checking those
  here would let two rules reject the same input with different reason codes,
  and the caller could not tell which contract governs the field. A test pins
  that R10 stays silent about an over-long `endToEndId`.
- **What this rule genuinely cannot do.** It cannot protect against a large
  request body. By the time it runs, Jackson has already read and materialised
  the payload — a 40MB body has already been parsed into memory. Request-size
  limiting belongs at the container (`server.max-http-request-header-size`,
  `server.tomcat.max-swallow-size`, or a filter). The total-character check is
  defence in depth against an oversized-but-parsed payload, not a substitute.
  **A "field lengths bounded" row in the README implies a protection this does
  not give** — say so there.

**Status:** implemented, 6 tests passing.
