"""The online counterpart to training/features.py's rolling-window features.

Redis sorted sets are the mechanism: the score is the event's Unix timestamp,
`ZREMRANGEBYSCORE` evicts everything older than the window before every read,
and `ZCARD` (or `ZRANGE` for the distinct-sender case) reads what is left. A
sorted set gives O(log n) eviction and O(1) cardinality reads, which is what
keeps this under the brief's 20ms p99 budget — a plain Redis list would need
an O(n) scan to find the expiry boundary on every single call.

Every window here is a *closed* interval computed BEFORE the current
transaction is recorded — record happens last, in `observe()`, mirroring the
training side's `closed='left'` rolling window (see training/features.py).
Get the score for a payment, THEN observe it; observing before scoring would
let a transaction see itself in its own velocity count, which the causal
design this whole feature set depends on explicitly forbids.
"""

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
    """Redis-backed rolling-window counters.

    One Redis instance is assumed shared across service replicas — the
    counters are correctness-critical shared state (two replicas scoring the
    same account must see the same recent history), not a per-process cache,
    so this is not the kind of state that can be sharded or made
    eventually-consistent without changing what the feature means.
    """

    def __init__(self, redis: Redis) -> None:
        self._redis = redis

    def read(self, debtor_account: str, creditor_account: str, now: float | None = None) -> dict[str, float]:
        """Returns the four velocity-derived features for a not-yet-recorded
        transaction between these two accounts. Pure read — does not observe.
        """
        now = now if now is not None else time.time()
        window_start = now - _WINDOW_SECONDS

        orig_key = _orig_key(debtor_account)
        dest_key = _dest_key(creditor_account)

        # Evict-then-read, both sides, in one round trip via a pipeline —
        # this pair of calls is the dominant cost in the 20ms p99 budget, so
        # it is not two separate Redis round trips.
        pipe = self._redis.pipeline(transaction=False)
        pipe.zremrangebyscore(orig_key, 0, window_start)
        pipe.zcard(orig_key)
        pipe.zremrangebyscore(dest_key, 0, window_start)
        pipe.zcard(dest_key)
        # Distinct senders needs the members, not just the count — ZRANGEBYSCORE
        # returns "sender_account:nonce" members (see observe()); the sender
        # is everything before the last ':'.
        pipe.zrangebyscore(dest_key, window_start, "+inf")
        _, orig_count, _, dest_count, dest_members = pipe.execute()

        distinct_senders = len({member.rsplit(":", 1)[0] for member in dest_members})

        return {
            "orig_prior_txn_count_24h": float(orig_count),
            "dest_prior_txn_count_24h": float(dest_count),
            "dest_prior_distinct_senders_24h": float(distinct_senders),
        }

    def observe(self, debtor_account: str, creditor_account: str, now: float | None = None) -> None:
        """Records this transaction so future calls see it. Call strictly
        after `read()` for the same transaction — see module docstring.
        """
        now = now if now is not None else time.time()

        pipe = self._redis.pipeline(transaction=False)
        # A nonce suffix, not just the timestamp, because ZADD's member must be
        # unique per entry: two transactions from the same account in the same
        # second would otherwise collide and one would silently overwrite the
        # other's score instead of both being counted.
        nonce = f"{now}:{id(object())}"
        pipe.zadd(_orig_key(debtor_account), {nonce: now})
        pipe.expire(_orig_key(debtor_account), _WINDOW_SECONDS)
        pipe.zadd(_dest_key(creditor_account), {f"{debtor_account}:{nonce}": now})
        pipe.expire(_dest_key(creditor_account), _WINDOW_SECONDS)
        pipe.execute()
