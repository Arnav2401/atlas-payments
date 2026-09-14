"""The single source of truth for what a "feature vector" is.

Training (training/features.py, pandas, offline, causal-by-construction over a
sorted DataFrame) and serving (fraud_service/features/, Redis, online) compute
the same eleven features from different code paths. That is a well-known way to
introduce training/serving skew — a feature that means something subtly
different at serve time than it meant during training, silently degrading a
model that tested well offline. Importing the names from here rather than
retyping them in both places is the (partial) guard against that: a renamed or
dropped feature on one side fails a test on the other rather than silently
producing NaN-filled columns.

See ``tests/test_feature_consistency.py`` for the other half of the guard: it
asserts the two implementations agree on the same synthetic transaction
sequence, not just on having the same column names.
"""

from __future__ import annotations

# Order matters: this is the column order the model is trained on and the order
# the serving predictor must assemble at inference time.
FEATURE_NAMES: list[str] = [
    "amount",
    "hour_of_day",
    "txn_type_is_cash_out",
    "orig_balance_ratio",
    "orig_balance_was_zero",
    "orig_prior_txn_count_24h",
    "orig_amount_zscore",
    "new_counterparty",
    "dest_prior_txn_count_24h",
    "dest_prior_distinct_senders_24h",
    "dest_balance_ratio",
    "dest_balance_was_zero",
]

# The rolling window used for every "_24h" feature. PaySim's `step` unit is one
# simulated hour, so this is 24 steps in training and 24 wall-clock hours in
# serving — the same window, expressed in whichever clock each side has.
VELOCITY_WINDOW_HOURS = 24

TARGET_COLUMN = "isFraud"

# The two columns PaySim provides that must never reach the model:
#
# `isFlaggedFraud` is a label produced by the simulator's own rule engine, not
# a feature a bank would observe independently. Measured on the full dataset:
# it fires on only 16 of 8,213 fraud cases (0.19% recall alone), and every one
# of those 16 is a real fraud (0 false positives) — it is not noise, it is a
# small, extremely precise rule baked into the simulation itself. Training on
# it would not "leak the future" the way a post-hoc label would, but it is
# still not a signal this service could compute independently in production,
# so it is dropped rather than smuggled in as a free 16-row accuracy boost.
#
# `nameOrig` / `nameDest` are raw identifiers, not features — see
# training/features.py for how they are consumed (as grouping keys for the
# velocity/history features) rather than encoded directly. A model that
# memorised specific account strings would not generalise past this dataset.
LEAKAGE_COLUMNS: list[str] = ["isFlaggedFraud"]
IDENTIFIER_COLUMNS: list[str] = ["nameOrig", "nameDest"]
