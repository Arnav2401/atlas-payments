"""Causal feature engineering: every feature at row *i* is computed only from
rows strictly before *i* in time. This is the offline (pandas, vectorised)
implementation; ``fraud_service/features/velocity.py`` is the online (Redis)
implementation of the same features, and the two must agree — see
``feature_spec.py`` and ``tests/test_feature_consistency.py``.

Why causal-by-construction matters: a "velocity" feature computed by grouping
the whole dataset and counting every transaction for an account — including
ones that happen after the row being scored — would report a PR-AUC that no
online system could ever reproduce, because at serving time the future has not
happened yet. This file is written so that mistake is structurally difficult:
every windowed feature is built from a cumulative or rolling-left aggregate on
data sorted by time, never from a plain ``groupby().transform()`` over the
whole frame.

Two measured facts about this specific dataset shaped what got built here
(fraud-eligible subset, TRANSFER + CASH_OUT only, 2,770,409 rows):

  - Origin accounts are almost entirely one-shot: 2,768,630 of 2,770,409 rows
    have a unique ``nameOrig`` — only 1,776 origin accounts transact more than
    once. "The account's own history" and "velocity" on the ORIGIN side are
    therefore cold-started (no prior data) for effectively every row in this
    dataset. That is a property of this simulator, not of production payment
    data, where the same debtor account transacts repeatedly — the code below
    is written for the general case and is exercised against synthetic
    repeat sequences in the test suite, independent of how often it actually
    fires on PaySim.

  - Destination accounts repeat substantially: 509,565 unique ``nameDest``
    across the same 2,770,409 rows, 381,234 of them (69%) appearing more than
    once, some as many as 75 times. That is real structure — a small number
    of accounts receiving many transactions from many different one-shot
    senders is the textbook shape of a money-laundering mule account (fan-in).
    So two features beyond the brief's literal list are added here, justified
    by what the data actually contains: `dest_prior_txn_count_24h` and
    `dest_prior_distinct_senders_24h`.
"""

from __future__ import annotations

import numpy as np
import pandas as pd

from fraud_service.feature_spec import VELOCITY_WINDOW_HOURS



def _rolling_count_causal(timestamps: np.ndarray, window_hours: float) -> np.ndarray:
    """For each i: the number of j < i with `timestamps[i] - timestamps[j] <=
    window_hours`. The plain-count sibling of `_rolling_distinct_count_causal`
    below — same two-pointer technique, no per-key tracking needed.

    This exists because the more obvious approach — pandas'
    `groupby(key)[col].rolling(window, closed='left').count()` — has a sharp
    edge that cost real debugging time: its result comes back ordered by
    group, not in the original row order, and assigning it back with a plain
    positional `.to_numpy()` silently scrambles which row gets which count.
    `test_feature_consistency.py` caught it (a dest count landed on the wrong
    row); the fix here is to use the same label-based `.loc[idx, ...]`
    assignment as `dest_prior_distinct_senders_24h` was already using
    correctly, rather than trust rolling's output order.
    """
    n = len(timestamps)
    result = np.zeros(n, dtype=np.int32)
    left = 0
    for i in range(n):
        while left < i and (timestamps[i] - timestamps[left]) > window_hours:
            left += 1
        result[i] = i - left
    return result


def _rolling_distinct_count_causal(timestamps: np.ndarray, keys: np.ndarray, window_hours: float) -> np.ndarray:
    """For each i: the number of DISTINCT `keys[j]` among j < i with
    `timestamps[i] - timestamps[j] <= window_hours`. Strictly causal — row i's
    own key is added to the window only after result[i] is recorded.

    Pandas has no vectorised "rolling nunique"; this is a two-pointer sliding
    window (each row enters and leaves the window at most once), so it is
    O(n) per group despite being a plain Python loop, rather than the O(n *
    window size) a naive `.rolling().apply(lambda w: w.nunique())` would cost.
    Applied via groupby over ~510k destination accounts rather than the ~2.77M
    row frame directly, which is what keeps it tractable.
    """
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
    """Returns a copy of df with every column in feature_spec.FEATURE_NAMES added.

    Requires `step`, `type`, `amount`, `nameOrig`, `nameDest`, `oldbalanceOrg`,
    `oldbalanceDest`. Does not require or use `newbalanceOrig`/`newbalanceDest`
    (the post-transaction balances) — those describe the transaction's own
    effect and would not be known at scoring time, before the transaction is
    posted. Using them would be a subtler, second leak.
    """
    out = df.sort_values("step", kind="mergesort").reset_index(drop=True).copy()
    ts_hours = out["step"].to_numpy(dtype=np.float64)

    out["hour_of_day"] = (out["step"] % 24).astype(np.int32)
    out["txn_type_is_cash_out"] = (out["type"] == "CASH_OUT").astype(np.int32)

    # --- balance-ratio features (origin and destination, symmetric treatment) ---
    for prefix, balance_col in (("orig", "oldbalanceOrg"), ("dest", "oldbalanceDest")):
        balance = out[balance_col]
        was_zero = (balance == 0).astype(np.int32)
        with np.errstate(divide="ignore", invalid="ignore"):
            ratio = np.where(balance > 0, out["amount"] / balance, np.nan)
        out[f"{prefix}_balance_ratio"] = ratio
        out[f"{prefix}_balance_was_zero"] = was_zero

    # --- origin: baseline vs own prior history (unbounded, causal) ---
    # Vectorised via cumulative sum / sum-of-squares rather than
    # groupby().expanding().apply(), which dispatches a Python call per group
    # and is impractical at ~2.5M mostly-singleton groups.
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

    # --- new-counterparty flag: has this (orig, dest) pair transacted before ---
    pair_key = out["nameOrig"].astype(str) + "\x1f" + out["nameDest"].astype(str)
    out["new_counterparty"] = (pair_key.groupby(pair_key).cumcount() == 0).astype(np.int32)

    # --- rolling-window velocity, origin and destination ---
    #
    # Counts are always well-defined — "zero prior transactions in the window"
    # and "no prior transactions at all" are the same state, so these are
    # never NaN, unlike orig_amount_zscore below, which genuinely has no value
    # at zero prior samples.
    #
    # All three windowed features below use the same hand-rolled two-pointer
    # functions and the same `.loc[idx, ...]` label-based assignment, on
    # purpose. An earlier version computed the two plain counts with pandas'
    # `groupby(key)[col].rolling(window, closed='left').count()` instead, which
    # is the more obvious way to write it — and it silently scrambled rows,
    # because that call's result comes back ordered by group, not in the
    # original row order, and a positional `.to_numpy()` assignment does not
    # know that. test_feature_consistency.py caught a dest count landing on
    # the wrong row. One mechanism, trusted once, used three times, rather
    # than mixing a subtly-wrong shortcut with the pattern already proven
    # correct for the distinct-sender count.
    # Skip singleton groups entirely, rather than looping over all ~2.5M of
    # them to learn what is already known: a `nameOrig` that appears exactly
    # once has no prior transaction in any window, so its count is 0 — see
    # this file's module docstring on how one-shot origin accounts are in
    # PaySim. Iterating every group in pure Python regardless of size was
    # originally over five minutes; `duplicated()` finds the ~1,776 accounts
    # that actually repeat in under a second, and only those need the loop.
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
