"""The online counterpart to training/features.py's *unbounded* causal
features — `orig_amount_zscore` and `new_counterparty`. Deliberately separate
from velocity.py: those are rolling-window (bounded, recent-burst) signals,
these are all-time baseline signals, and the two answer different questions
("is this account unusually busy right now" vs "is this amount unusual for
this account, ever"). Training keeps the same split — see features.py.
"""

from __future__ import annotations

from redis import Redis


def _history_key(account_number: str) -> str:
    return f"history:orig:{account_number}"


def _pair_key(debtor_account: str, creditor_account: str) -> str:
    return f"pair:{debtor_account}:{creditor_account}"


class AccountHistory:
    """Running count/sum/sum-of-squares per origin account, and a set of every
    (debtor, creditor) pair ever seen — the online equivalent of the
    cumulative-sum algebra in training/features.py's `add_causal_features`.

    A Redis HASH per account (three numeric fields) rather than storing every
    individual amount: the z-score only needs the first two moments, and this
    keeps a single account's history O(1) in space regardless of how many
    transactions it accumulates, instead of growing an ever-longer list.
    """

    def __init__(self, redis: Redis) -> None:
        self._redis = redis

    def read(self, debtor_account: str, creditor_account: str) -> dict[str, float]:
        """Pure read of prior state — does not record this transaction."""
        key = _history_key(debtor_account)
        count, total, total_sq = self._redis.hmget(key, "count", "sum", "sumsq")
        prior_count = int(count) if count else 0

        zscore_inputs = {
            "_prior_count": prior_count,
            "_prior_sum": float(total) if total else 0.0,
            "_prior_sumsq": float(total_sq) if total_sq else 0.0,
        }

        is_new_pair = not self._redis.sismember("pairs", _pair_key(debtor_account, creditor_account))
        zscore_inputs["new_counterparty"] = 1.0 if is_new_pair else 0.0
        return zscore_inputs

    def observe(self, debtor_account: str, creditor_account: str, amount: float) -> None:
        key = _history_key(debtor_account)
        pipe = self._redis.pipeline(transaction=False)
        pipe.hincrbyfloat(key, "count", 1)
        pipe.hincrbyfloat(key, "sum", amount)
        pipe.hincrbyfloat(key, "sumsq", amount * amount)
        pipe.sadd("pairs", _pair_key(debtor_account, creditor_account))
        pipe.execute()


def zscore(amount: float, prior_count: int, prior_sum: float, prior_sumsq: float) -> float | None:
    """Same algebra as training/features.py's vectorised version, applied to a
    single row: population mean/variance of the prior samples, via the
    sum/sum-of-squares identity rather than storing every individual amount.
    Returns None (-> NaN at the model boundary) when there is no prior
    history or the prior samples had zero variance — XGBoost treats a missing
    feature as a value to split on, not as an error; see predictor.py.
    """
    if prior_count == 0:
        return None
    mean = prior_sum / prior_count
    mean_sq = prior_sumsq / prior_count
    variance = max(mean_sq - mean * mean, 0.0)  # guards float error near zero
    std = variance**0.5
    if std == 0.0:
        return None
    return (amount - mean) / std
