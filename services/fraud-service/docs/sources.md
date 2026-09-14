# Data source

| Field | Value |
|---|---|
| Dataset | PaySim1 — "Synthetic Financial Datasets For Fraud Detection" |
| Original publisher | E. A. Lopez-Rojas, A. Elmir, S. Ahmed (Kaggle, `ealaxi/paysim1`) |
| Mirror used | [`theman10/paysim`](https://huggingface.co/datasets/theman10/paysim) on Hugging Face |
| Why a mirror | This machine had no Kaggle API credentials configured; the Hugging Face mirror is public, ungated, and MIT-licensed |
| Rows | 6,362,620 |
| Accessed | 2026-09-14 |
| Verified | Schema, row count, and license checked against the Hugging Face dataset API before use — not assumed from the mirror's description |

## Not committed to this repository

The raw CSV is 494MB. Per the project convention (see the root `.gitignore`),
large reference data is documented here, not committed. To reproduce training:

```bash
curl -L -o paysim.csv https://huggingface.co/datasets/theman10/paysim/resolve/main/paysim.csv
uv run python -m training.train --data /path/to/paysim.csv
```

`models/metrics.json` (committed) is the exact output of the last real run of
that command against the real file — every number in the main README's model
metrics table traces back to it.

## What was verified empirically before designing around it

Every claim below was measured against the actual downloaded file, not
recalled from familiarity with PaySim as a dataset. See `training/data.py`
and `training/features.py` for where each finding shows up in the design.

- Fraud exists **only** in `TRANSFER` and `CASH_OUT` transactions — zero fraud
  rows in `CASH_IN`, `DEBIT`, or `PAYMENT`.
- `isFlaggedFraud` fires on 16 of 8,213 fraud rows (0.19% recall alone), and
  every firing is a true positive — a simulator-internal rule, not an
  independently observable feature.
- Origin accounts are almost entirely one-shot: 2,768,630 of 2,770,409
  TRANSFER/CASH_OUT rows have a unique `nameOrig`.
- Destination accounts repeat substantially: 509,565 unique `nameDest`, 69%
  of them appearing more than once, up to 75 times.
- Transaction volume across the 743 simulated steps is highly non-uniform
  (median 112 rows/step, ranging from 23,768 down to single digits), and the
  sparse tail has a fraud rate roughly 10x the rest of the series — the reason
  `temporal_split` cuts by row count in chronological order, not by step value.
- 97.8% of fraudulent transactions drain the debtor's balance to within 1%,
  versus 0.15% of legitimate ones — the reason `orig_balance_ratio` dominates
  the model's SHAP values, and the reason the Java-side circuit-breaker
  fallback rule exists in the shape it does (see
  `com.atlas.payments.fraud.ConservativeRuleFallback`).
