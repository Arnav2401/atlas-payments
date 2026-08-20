# Validation specification

Rules enforced on inbound `pacs.008` messages. Each rule is independently
implementable and independently testable. Rule IDs are stable — they map 1:1
to test classes in the ingestion service.

Rejection responses are `pacs.002` (FIToFIPaymentStatusReport) with status
`RJCT` and a reason code from `ExternalStatusReason1Code`.

---

## R-001 — Debtor postal address must be structured or hybrid

- **Field:** `/Document/FIToFICstmrCdtTrf/CdtTrfTxInf/Dbtr/PstlAdr`
- **Condition:** If `PstlAdr` is present, both `Ctry` (ISO 3166-1 alpha-2) and
  `TwnNm` must be present and non-empty. `AdrLine` may occur at most twice and
  may not be the sole content of the element.
- **Applies from:** 2026-11-14 (SR2026 / CBPR+)
- **On failure:** `RJCT`, reason code `RR02` — verify against S4
- **Rationale:** Unstructured addresses cannot be reliably parsed by sanctions
  screening engines. Fully unstructured addresses are withdrawn at network
  level from this date, so the message would be rejected upstream regardless.
- **Source:** S1, S4
- **Test cases:**
  - valid — `Ctry=IN`, `TwnNm=Jaipur`, no `AdrLine`
  - valid — hybrid: `Ctry=IN`, `TwnNm=Jaipur`, one `AdrLine`
  - invalid — three `AdrLine`, no `Ctry`, no `TwnNm`
  - edge — `Ctry=IN`, `TwnNm=""` (present but empty)

---

## R-002 — <your rule>
