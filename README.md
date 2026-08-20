# atlas-payments

A cross-border payment processing and financial-crime detection platform.

Ingests ISO 20022 `pacs.008` messages, validates them against SWIFT SR2026
rules, screens all parties against sanctions lists, scores each payment for
fraud with an explainable model, and settles to a double-entry ledger.

**Status:** Phase 00 — domain grounding

## Documentation

- [`docs/validation-spec.md`](docs/validation-spec.md) — the validation rules this system enforces
- [`docs/domain-map.md`](docs/domain-map.md) — how a cross-border payment actually moves
- [`docs/sources.md`](docs/sources.md) — primary sources and access dates
- [`DESIGN_NOTES.md`](DESIGN_NOTES.md) — decision log
