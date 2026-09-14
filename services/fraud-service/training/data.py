from __future__ import annotations

from pathlib import Path

import pandas as pd

from fraud_service.feature_spec import IDENTIFIER_COLUMNS, LEAKAGE_COLUMNS

# Fraud exists only in these two types. Training on the rest lets the model hit
# near-perfect scores off "type not in {TRANSFER, CASH_OUT}", which is a fact
# about the simulator, not a pattern about fraud.
FRAUD_ELIGIBLE_TYPES = ("TRANSFER", "CASH_OUT")


def load_raw(csv_path: str | Path) -> pd.DataFrame:
    df = pd.read_csv(csv_path)
    return df


def scope_to_fraud_eligible_types(df: pd.DataFrame) -> pd.DataFrame:
    scoped = df[df["type"].isin(FRAUD_ELIGIBLE_TYPES)].copy()
    scoped = scoped.drop(columns=[c for c in LEAKAGE_COLUMNS if c in scoped.columns])
    return scoped


def temporal_split(df: pd.DataFrame, train_fraction: float = 0.8) -> tuple[pd.DataFrame, pd.DataFrame]:
    """Holds out the last fraction of rows in time order.

    By row count, not by `step` value. Volume per step is wildly uneven (median
    112 rows, peak 23,768) and the sparse tail runs about 10x the fraud rate, so
    cutting at 80% of the step range put a tiny, atypical, fraud-dense slice in
    the test set and scored near-perfect against it.
    """
    cutoff_index = int(len(df) * train_fraction)
    train = df.iloc[:cutoff_index]
    test = df.iloc[cutoff_index:]
    return train, test


def drop_identifiers(df: pd.DataFrame) -> pd.DataFrame:
    return df.drop(columns=[c for c in IDENTIFIER_COLUMNS if c in df.columns])
