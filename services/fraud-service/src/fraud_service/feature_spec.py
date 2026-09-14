from __future__ import annotations

# Order matters: this is the column order the model trains on, and the order the
# serving path must rebuild at inference time.
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

VELOCITY_WINDOW_HOURS = 24

TARGET_COLUMN = "isFraud"

# isFlaggedFraud is the simulator's own rule firing, not something a bank could
# observe independently. It catches 16 of 8,213 frauds, all true positives.
LEAKAGE_COLUMNS: list[str] = ["isFlaggedFraud"]
IDENTIFIER_COLUMNS: list[str] = ["nameOrig", "nameDest"]
