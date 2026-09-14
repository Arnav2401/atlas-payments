from __future__ import annotations

from dataclasses import dataclass

from fraud_service.feature_spec import FEATURE_NAMES
from fraud_service.features.history import AccountHistory, zscore
from fraud_service.features.velocity import VelocityFeatures


@dataclass(frozen=True)
class PaymentEvent:
    amount: float
    hour_of_day: int  # 0-23. Caller's responsibility: see api/schemas.py.
    is_cash_out: bool  # the PaySim-derived txn_type_is_cash_out flag
    debtor_account: str
    creditor_account: str
    debtor_balance_before: float
    creditor_balance_before: float
    now: float | None = None


class FeatureEngineer:
    def __init__(self, velocity: VelocityFeatures, history: AccountHistory) -> None:
        self._velocity = velocity
        self._history = history

    def compute(self, event: PaymentEvent) -> dict[str, float | None]:
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

        assert set(features.keys()) == set(FEATURE_NAMES), (
            f"feature engineering produced {sorted(features.keys())}, "
            f"expected {sorted(FEATURE_NAMES)}"
        )
        return features

    def record(self, event: PaymentEvent) -> None:
        self._velocity.observe(event.debtor_account, event.creditor_account, now=event.now)
        self._history.observe(event.debtor_account, event.creditor_account, event.amount)
