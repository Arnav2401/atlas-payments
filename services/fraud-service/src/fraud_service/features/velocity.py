from __future__ import annotations

import time

from redis import Redis

from fraud_service.feature_spec import VELOCITY_WINDOW_HOURS

_WINDOW_SECONDS = VELOCITY_WINDOW_HOURS * 3600


def _orig_key(account_number: str) -> str:
    return f"velocity:orig:{account_number}"


def _dest_key(account_number: str) -> str:
    return f"velocity:dest:{account_number}"


class VelocityFeatures:
    """Redis sliding-window counters: the online twin of training/features.py."""

    def __init__(self, redis: Redis) -> None:
        self._redis = redis

    def read(self, debtor_account: str, creditor_account: str, now: float | None = None) -> dict[str, float]:
        now = now if now is not None else time.time()
        window_start = now - _WINDOW_SECONDS

        orig_key = _orig_key(debtor_account)
        dest_key = _dest_key(creditor_account)

        pipe = self._redis.pipeline(transaction=False)
        pipe.zremrangebyscore(orig_key, 0, window_start)
        pipe.zcard(orig_key)
        pipe.zremrangebyscore(dest_key, 0, window_start)
        pipe.zcard(dest_key)
        pipe.zrangebyscore(dest_key, window_start, "+inf")
        _, orig_count, _, dest_count, dest_members = pipe.execute()

        distinct_senders = len({member.rsplit(":", 1)[0] for member in dest_members})

        return {
            "orig_prior_txn_count_24h": float(orig_count),
            "dest_prior_txn_count_24h": float(dest_count),
            "dest_prior_distinct_senders_24h": float(distinct_senders),
        }

    def observe(self, debtor_account: str, creditor_account: str, now: float | None = None) -> None:
        now = now if now is not None else time.time()

        pipe = self._redis.pipeline(transaction=False)
        nonce = f"{now}:{id(object())}"
        pipe.zadd(_orig_key(debtor_account), {nonce: now})
        pipe.expire(_orig_key(debtor_account), _WINDOW_SECONDS)
        pipe.zadd(_dest_key(creditor_account), {f"{debtor_account}:{nonce}": now})
        pipe.expire(_dest_key(creditor_account), _WINDOW_SECONDS)
        pipe.execute()
