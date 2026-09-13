# Design notes

Decision log. Newest first. Every non-obvious choice gets an entry, written
when the decision is made — not reconstructed later.

Format:
  ## YYYY-MM-DD — <decision>
  **Chose:** ...
  **Over:** ...
  **Because:** ...
  **What broke:** ...

---

## TODO — write this entry yourself (2026-09-14 scope change)

The pacs.008 decision below was superseded today: real ISO 20022 / SWIFT
compliance is now out of scope, and the API takes ISO-20022-flavoured JSON
instead. The code and docs already reflect that.

This entry is deliberately blank. Claude scaffolded the code; it did not make
this call and should not write the reasoning. Fill in chose / over / because /
what broke in your own words — "what broke" is the unfakeable field and it is
exactly what gets asked.

---

## 2026-08-20 — Core message format
**Chose:** ISO 20022 `pacs.008` (FIToFICustomerCreditTransfer) as the system's
core inbound message.
**Over:** SWIFT MT103, the legacy equivalent.
**Because:** MT payment instructions were retired from the SWIFT network in
November 2025, and from 14 November 2026 SR2026 requires banks to natively
originate and process ISO 20022 under tighter validation rules. Building
against MT103 would model a system that no longer exists.
**What broke:** n/a — no code yet.
**Superseded:** 2026-09-14, see the entry above.
