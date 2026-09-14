"""Assembles one row of feature_spec.FEATURE_NAMES for a single incoming
payment — the online equivalent of training/features.py's vectorised
`add_causal_features`, applied to one transaction instead of a whole
DataFrame.

The non-negotiable rule this file exists to enforce: **read every stateful
feature from Redis before recording this transaction into Redis.** Reversing
that order would let a payment see itself in its own velocity count and its
own new-counterparty flag — the single easiest way to quietly reintroduce the
leakage training/features.py was built causal-by-construction to avoid. See
`tests/test_feature_consistency.py` for the test that would catch it.
"""

from __future__ import annotations

from dataclasses import dataclass

from fraud_service.feature_spec import FEATURE_NAMES
from fraud_service.features.history import AccountHistory, zscore
from fraud_service.features.velocity import VelocityFeatures


@dataclass(frozen=True)
class PaymentEvent:
    """The subset of a payment this service needs. Field names match the
    ledger's own vocabulary (see LedgerWriter on the Java side), not PaySim's
    column names — PaySim's schema was the training data's shape, not this
    service's API contract.
    """

    amount: float
    hour_of_day: int  # 0-23. Caller's responsibility: see api/schemas.py.
    is_cash_out: bool  # the PaySim-derived txn_type_is_cash_out flag
    debtor_account: str
    creditor_account: str
    debtor_balance_before: float
    creditor_balance_before: float
    # None in production: VelocityFeatures then uses real wall-clock time, which
    # is what makes the 24h window actually mean 24 wall-clock hours in
    # serving. Tests set this explicitly, to a synthetic clock derived from
    # PaySim's `step`, so the window-expiry boundary can be exercised and
    # compared against training/features.py's step-based window without
    # waiting 24 real hours or faking the system clock.
    now: float | None = None


class FeatureEngineer:
    def __init__(self, velocity: VelocityFeatures, history: AccountHistory) -> None:
        self._velocity = velocity
        self._history = history

    def compute(self, event: PaymentEvent) -> dict[str, float | None]:
        """Read-only: does not record the event. Call `record()` separately,
        strictly after scoring, so a failed or rejected payment is never
        counted into future velocity — see routes.py for where that ordering
        is enforced.
        """
        velocity_features = self._velocity.read(event.debtor_account, event.creditor_account, now=event.now)
        history_state = self._history.read(event.debtor_account, event.creditor_account)

        orig_ratio = event.amount / event.debtor_balance_before if event.debtor_balance_before > 0 else None
        dest_ratio = event.amount / event.creditor_balance_before if event.creditor_balance_before > 0 else None

        features: dict[str, float | None] = {
            "amount": event.amount,
            "hour_of_day": event.hour_of_day,
            "txn_type_is_cash_out": 1.0 if event.is_cash_out else 0.0,
            "orig_balance_ratio": orig_ratio,
            "orig_balance_was_zero": 1.0 if event.debtor_balance_before == 0 else 0.0,
            "orig_prior_txn_count_24h": velocity_features["orig_prior_txn_count_24h"],
            "orig_amount_zscore": zscore(
                event.amount,
                int(history_state["_prior_count"]),
                history_state["_prior_sum"],
                history_state["_prior_sumsq"],
            ),
            "new_counterparty": history_state["new_counterparty"],
            "dest_prior_txn_count_24h": velocity_features["dest_prior_txn_count_24h"],
            "dest_prior_distinct_senders_24h": velocity_features["dest_prior_distinct_senders_24h"],
            "dest_balance_ratio": dest_ratio,
            "dest_balance_was_zero": 1.0 if event.creditor_balance_before == 0 else 0.0,
        }

        # Fails loudly, at import-adjacent risk rather than silently scoring
        # against a misaligned vector, if this module and feature_spec drift.
        assert set(features.keys()) == set(FEATURE_NAMES), (
            f"feature engineering produced {sorted(features.keys())}, "
            f"expected {sorted(FEATURE_NAMES)}"
        )
        return features

    def record(self, event: PaymentEvent) -> None:
        """Observes this transaction so future reads see it. Call once, after
        scoring, never before.
        """
        self._velocity.observe(event.debtor_account, event.creditor_account, now=event.now)
        self._history.observe(event.debtor_account, event.creditor_account, event.amount)
