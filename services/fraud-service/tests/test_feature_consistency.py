"""Training (pandas, offline) and serving (Redis, online) implement the same
eleven features from two different code paths. This is the test that would
catch training/serving skew — the two disagreeing about what a feature means
— which `feature_spec.py`'s shared names alone cannot catch, since agreeing on
a name says nothing about agreeing on a value.

Replays the same synthetic transaction sequence through both paths and asserts
they compute matching numbers row by row. Synthetic, not PaySim: this test is
about a mechanism (do the two implementations agree), not about measuring
model performance, and a small hand-built sequence with known repeat accounts
exercises the non-cold-start branches that PaySim's real data — being almost
entirely one-shot on the origin side, see training/features.py — barely does.
"""

from __future__ import annotations

import fakeredis
import pandas as pd
import pytest

from fraud_service.feature_spec import FEATURE_NAMES
from fraud_service.features.engineering import FeatureEngineer, PaymentEvent
from fraud_service.features.history import AccountHistory
from fraud_service.features.velocity import VelocityFeatures
from training.features import add_causal_features

# A hand-built sequence with real repeat structure: ACC-A pays three times
# (exercises orig velocity + baseline), ACC-C receives from two different
# senders within the window (exercises dest fan-in), and the last row is
# placed 100 hours after everything else — well outside the 24h window — to
# exercise EXPIRY, not just accumulation. Without a row like this, both a
# correct implementation and one that never evicts anything would pass the
# same test. `step` is hours.
SEQUENCE = pd.DataFrame(
    [
        {"step": 1, "type": "TRANSFER", "amount": 100.0, "nameOrig": "ACC-A", "oldbalanceOrg": 1000.0,
         "nameDest": "ACC-C", "oldbalanceDest": 0.0},
        {"step": 2, "type": "CASH_OUT", "amount": 200.0, "nameOrig": "ACC-A", "oldbalanceOrg": 900.0,
         "nameDest": "ACC-D", "oldbalanceDest": 500.0},
        {"step": 3, "type": "TRANSFER", "amount": 50.0, "nameOrig": "ACC-B", "oldbalanceOrg": 300.0,
         "nameDest": "ACC-C", "oldbalanceDest": 100.0},
        {"step": 10, "type": "TRANSFER", "amount": 900.0, "nameOrig": "ACC-A", "oldbalanceOrg": 700.0,
         "nameDest": "ACC-C", "oldbalanceDest": 150.0},
        {"step": 110, "type": "TRANSFER", "amount": 40.0, "nameOrig": "ACC-A", "oldbalanceOrg": 400.0,
         "nameDest": "ACC-C", "oldbalanceDest": 300.0},
    ]
)

_EPOCH = pd.Timestamp("2023-01-01")


def _now_for_step(step: int) -> float:
    """Maps a PaySim `step` (simulated hours since an arbitrary epoch) to a
    Unix timestamp, so VelocityFeatures — which speaks Unix time, since that
    is what a real Redis deployment's clock gives it — can be driven by the
    same step values training/features.py's rolling windows use directly
    (there, as raw hour differences; here, as an absolute clock).
    """
    return (_EPOCH + pd.Timedelta(hours=step)).timestamp()


@pytest.fixture
def engineer() -> FeatureEngineer:
    redis_client = fakeredis.FakeStrictRedis(decode_responses=True)
    return FeatureEngineer(velocity=VelocityFeatures(redis_client), history=AccountHistory(redis_client))


def test_serving_matches_training_row_by_row(engineer: FeatureEngineer) -> None:
    training_features = add_causal_features(SEQUENCE)

    for i, row in SEQUENCE.iterrows():
        event = PaymentEvent(
            amount=row["amount"],
            hour_of_day=int(row["step"] % 24),
            is_cash_out=(row["type"] == "CASH_OUT"),
            debtor_account=row["nameOrig"],
            creditor_account=row["nameDest"],
            debtor_balance_before=row["oldbalanceOrg"],
            creditor_balance_before=row["oldbalanceDest"],
            # Driven by the same step-derived clock training's rolling window
            # uses (see _now_for_step above), so the 24h Redis window expires
            # entries at the same boundary the training side's `closed='left'`
            # rolling window does — including the step=110 row genuinely
            # falling outside every earlier row's window.
            now=_now_for_step(int(row["step"])),
        )

        served = engineer.compute(event)
        engineer.record(event)

        expected = training_features.loc[training_features["step"] == row["step"]].iloc[0]

        for name in FEATURE_NAMES:
            served_value = served[name]
            expected_value = expected[name]
            if pd.isna(expected_value):
                assert served_value is None, f"row {i} feature {name}: training=NaN, serving={served_value}"
            else:
                assert served_value == pytest.approx(float(expected_value), abs=1e-6), (
                    f"row {i} feature {name}: training={expected_value}, serving={served_value}"
                )


def test_read_before_record_is_the_only_correct_order(engineer: FeatureEngineer) -> None:
    """The ordering rule stated in engineering.py's docstring, made executable:
    reading twice without recording in between must be idempotent (a payment
    that never actually posts must not pollute future velocity), and a
    transaction must never see itself in its own count.
    """
    event = PaymentEvent(
        amount=500.0, hour_of_day=12, is_cash_out=True,
        debtor_account="ACC-X", creditor_account="ACC-Y",
        debtor_balance_before=1000.0, creditor_balance_before=0.0,
    )

    first_read = engineer.compute(event)
    second_read = engineer.compute(event)
    assert first_read == second_read, "a read with no record in between must be idempotent"
    assert first_read["orig_prior_txn_count_24h"] == 0.0
    assert first_read["new_counterparty"] == 1.0

    engineer.record(event)

    after_record = engineer.compute(event)
    assert after_record["orig_prior_txn_count_24h"] == 1.0, "must see its own prior submission"
    assert after_record["new_counterparty"] == 0.0
