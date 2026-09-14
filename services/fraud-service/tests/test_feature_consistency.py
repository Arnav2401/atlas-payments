from __future__ import annotations

import fakeredis
import pandas as pd
import pytest

from fraud_service.feature_spec import FEATURE_NAMES
from fraud_service.features.engineering import FeatureEngineer, PaymentEvent
from fraud_service.features.history import AccountHistory
from fraud_service.features.velocity import VelocityFeatures
from training.features import add_causal_features

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
