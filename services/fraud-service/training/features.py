from __future__ import annotations

import numpy as np
import pandas as pd

from fraud_service.feature_spec import VELOCITY_WINDOW_HOURS


def _rolling_count_causal(timestamps: np.ndarray, window_hours: float) -> np.ndarray:
    n = len(timestamps)
    result = np.zeros(n, dtype=np.int32)
    left = 0
    for i in range(n):
        while left < i and (timestamps[i] - timestamps[left]) > window_hours:
            left += 1
        result[i] = i - left
    return result


def _rolling_distinct_count_causal(timestamps: np.ndarray, keys: np.ndarray, window_hours: float) -> np.ndarray:
    n = len(timestamps)
    result = np.zeros(n, dtype=np.int32)
    window_counts: dict = {}
    left = 0
    for i in range(n):
        while left < i and (timestamps[i] - timestamps[left]) > window_hours:
            key = keys[left]
            window_counts[key] -= 1
            if window_counts[key] == 0:
                del window_counts[key]
            left += 1
        result[i] = len(window_counts)
        key = keys[i]
        window_counts[key] = window_counts.get(key, 0) + 1
    return result


def add_causal_features(df: pd.DataFrame) -> pd.DataFrame:
    out = df.sort_values("step", kind="mergesort").reset_index(drop=True).copy()
    ts_hours = out["step"].to_numpy(dtype=np.float64)

    out["hour_of_day"] = (out["step"] % 24).astype(np.int32)
    out["txn_type_is_cash_out"] = (out["type"] == "CASH_OUT").astype(np.int32)

    for prefix, balance_col in (("orig", "oldbalanceOrg"), ("dest", "oldbalanceDest")):
        balance = out[balance_col]
        was_zero = (balance == 0).astype(np.int32)
        with np.errstate(divide="ignore", invalid="ignore"):
            ratio = np.where(balance > 0, out["amount"] / balance, np.nan)
        out[f"{prefix}_balance_ratio"] = ratio
        out[f"{prefix}_balance_was_zero"] = was_zero

    grp_orig = out.groupby("nameOrig")["amount"]
    prior_count_orig = grp_orig.cumcount().astype(np.float64)  # rows strictly before this one, same account
    cumsum_incl = grp_orig.cumsum()
    prior_sum = cumsum_incl - out["amount"]
    prior_mean = prior_sum / prior_count_orig.replace(0, np.nan)

    amount_sq = out["amount"] ** 2
    cumsumsq_incl = amount_sq.groupby(out["nameOrig"]).cumsum()
    prior_sumsq = cumsumsq_incl - amount_sq
    prior_mean_sq = prior_sumsq / prior_count_orig.replace(0, np.nan)
    prior_var = (prior_mean_sq - prior_mean**2).clip(lower=0)  # guards float error near zero
    prior_std = np.sqrt(prior_var)

    out["orig_amount_zscore"] = (out["amount"] - prior_mean) / prior_std.replace(0, np.nan)

    pair_key = out["nameOrig"].astype(str) + "\x1f" + out["nameDest"].astype(str)
    out["new_counterparty"] = (pair_key.groupby(pair_key).cumcount() == 0).astype(np.int32)

    out["orig_prior_txn_count_24h"] = 0
    repeated_orig = out[out["nameOrig"].duplicated(keep=False)]
    for _, group in repeated_orig.groupby("nameOrig", sort=False):
        idx = group.index.to_numpy()
        out.loc[idx, "orig_prior_txn_count_24h"] = _rolling_count_causal(
            ts_hours[idx], float(VELOCITY_WINDOW_HOURS)
        )

    out["dest_prior_txn_count_24h"] = 0
    out["dest_prior_distinct_senders_24h"] = 0
    for _, group in out.groupby("nameDest", sort=False):
        idx = group.index.to_numpy()
        group_ts = ts_hours[idx]
        out.loc[idx, "dest_prior_txn_count_24h"] = _rolling_count_causal(group_ts, float(VELOCITY_WINDOW_HOURS))
        out.loc[idx, "dest_prior_distinct_senders_24h"] = _rolling_distinct_count_causal(
            group_ts, group["nameOrig"].to_numpy(), float(VELOCITY_WINDOW_HOURS)
        )

    return out
