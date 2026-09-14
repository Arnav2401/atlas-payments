"""Loads and scopes the PaySim dataset.

Every number in the docstrings below was measured against the actual 6,362,620
row file (theman10/paysim on Hugging Face, MIT licensed, a public mirror of the
Kaggle "Synthetic Financial Datasets For Fraud Detection" / PaySim1 dataset —
see docs/sources.md), not recalled from memory or copied from another writeup.
Re-run ``python -m training.verify_data`` against the CSV to reproduce them.
"""

from __future__ import annotations

from pathlib import Path

import pandas as pd

from fraud_service.feature_spec import IDENTIFIER_COLUMNS, LEAKAGE_COLUMNS

# Fraud in PaySim exists ONLY in these two transaction types. Measured:
#
#     type       fraud_count   total_count
#     CASH_IN              0       1399284
#     CASH_OUT          4116       2237500
#     DEBIT                0         41432
#     PAYMENT              0       2151495
#     TRANSFER          4097        532909
#
# Training on the full population would let the model — or a much simpler
# rule — achieve near-perfect apparent performance from "type not in
# {TRANSFER, CASH_OUT}" alone, which is a fact about this simulator's design,
# not a discriminative pattern about fraud. Restricting to the two types the
# fraud actually lives in is the standard treatment for this dataset and the
# only way PR-AUC reported here means what it claims to mean.
#
# This is also the shape of a real, defensible production decision: the same
# restriction becomes a cheap rules pre-filter ("only route TRANSFER/CASH_OUT
# to the ML model; everything else has a measured base rate of zero and does
# not need it") rather than a modelling trick — it is the same idea M3's
# circuit-breaker fallback uses, applied one layer up.
FRAUD_ELIGIBLE_TYPES = ("TRANSFER", "CASH_OUT")


def load_raw(csv_path: str | Path) -> pd.DataFrame:
    df = pd.read_csv(csv_path)
    return df


def scope_to_fraud_eligible_types(df: pd.DataFrame) -> pd.DataFrame:
    scoped = df[df["type"].isin(FRAUD_ELIGIBLE_TYPES)].copy()
    scoped = scoped.drop(columns=[c for c in LEAKAGE_COLUMNS if c in scoped.columns])
    return scoped


def temporal_split(df: pd.DataFrame, train_fraction: float = 0.8) -> tuple[pd.DataFrame, pd.DataFrame]:
    """Splits by `step` (time), not randomly — the last `1 - train_fraction`
    of ROWS in chronological order become the test set, not the last fraction
    of the step *range*.

    A random row-level split is the wrong evaluation for transaction data and
    would overstate performance: rows from the same account could land on
    both sides of the split even though the causal features are computed as
    of a single global time order, and it would not test what actually
    matters — can the model score payments it has never seen the future of.
    Held-out-by-time is the honest analogue of how this model is actually
    used: trained on the past, scoring what arrives next.

    <b>This split by ROW COUNT, not step value, on purpose — an earlier
    version did the latter and it was a real bug, not a style choice.</b>
    Measured: transaction volume across the 743 steps is wildly non-uniform
    (median 112 rows/step, but ranging from 23,768 down to single digits) and
    the sparse tail has a fraud rate roughly 10x the rest of the series
    (~1.4% in the last decile of steps vs ~0.1-0.4% elsewhere). Cutting at
    "80% of the step *range*" put a small, atypical, fraud-dense sliver of
    51,286 rows (1.9% of the data) into the test set — and against that
    unrepresentative slice, PR-AUC and precision-at-fixed-recall both came
    back implausibly close to perfect. That was a bug in the evaluation, not
    a good result, caught by treating a too-good number as a reason to look
    closer rather than as a reason to write it down. Splitting by row count
    along the same chronological order fixes the sizing and the skew while
    keeping every property that makes the split causal in the first place —
    the test set is still strictly later in time than the train set.
    """
    cutoff_index = int(len(df) * train_fraction)
    train = df.iloc[:cutoff_index]
    test = df.iloc[cutoff_index:]
    return train, test


def drop_identifiers(df: pd.DataFrame) -> pd.DataFrame:
    """Drops the raw account-id columns after every feature that needs them as a
    grouping key has already been computed. See feature_spec.IDENTIFIER_COLUMNS
    for why these never reach the model directly.
    """
    return df.drop(columns=[c for c in IDENTIFIER_COLUMNS if c in df.columns])
