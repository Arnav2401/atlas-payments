from __future__ import annotations

from pathlib import Path

import fakeredis
from fastapi.testclient import TestClient

from fraud_service.api.routes import router
from fraud_service.feature_spec import FEATURE_NAMES
from fraud_service.features.engineering import FeatureEngineer
from fraud_service.features.history import AccountHistory
from fraud_service.features.velocity import VelocityFeatures
from fraud_service.model.predictor import FraudPredictor
from fraud_service.scoring import ScoringService
from fastapi import FastAPI


def _build_app(model_dir: Path) -> FastAPI:
    app = FastAPI()
    app.include_router(router)
    predictor = FraudPredictor(model_dir)
    redis_client = fakeredis.FakeStrictRedis(decode_responses=True)
    engineer = FeatureEngineer(velocity=VelocityFeatures(redis_client), history=AccountHistory(redis_client))
    app.state.scoring_service = ScoringService(engineer=engineer, predictor=predictor)
    app.state.feature_engineer = engineer
    app.state.neo4j_driver = None
    return app


def _valid_payload(**overrides) -> dict:
    payload = {
        "end_to_end_id": "E2E-001",
        "amount": 500.0,
        "is_cash_out": True,
        "debtor_account": "ACC-A",
        "creditor_account": "ACC-B",
        "debtor_balance_before": 500.0,
        "creditor_balance_before": 0.0,
        "hour_of_day": 3,
    }
    payload.update(overrides)
    return payload


def test_health(tiny_model_dir: Path) -> None:
    client = TestClient(_build_app(tiny_model_dir))
    response = client.get("/health")
    assert response.status_code == 200


def test_score_returns_probability_and_exactly_three_top_features(tiny_model_dir: Path) -> None:
    client = TestClient(_build_app(tiny_model_dir))

    response = client.post("/score", json=_valid_payload())

    assert response.status_code == 200
    body = response.json()
    assert 0.0 <= body["probability"] <= 1.0
    assert isinstance(body["flagged"], bool)
    assert len(body["top_features"]) == 3
    for feature in body["top_features"]:
        assert feature["feature"] in FEATURE_NAMES, f"unrecognised feature name: {feature['feature']}"
        assert isinstance(feature["shap_contribution"], float)


def test_a_second_call_sees_the_first_as_prior_velocity(tiny_model_dir: Path) -> None:
    app = _build_app(tiny_model_dir)
    client = TestClient(app)

    client.post("/score", json=_valid_payload(end_to_end_id="E2E-1"))
    client.post("/score", json=_valid_payload(end_to_end_id="E2E-2"))

    engineer: FeatureEngineer = app.state.feature_engineer
    velocity = engineer._velocity.read(  # noqa: SLF001 - white-box check of Redis state, not public API
        debtor_account="ACC-A", creditor_account="ACC-B"
    )
    assert velocity["orig_prior_txn_count_24h"] == 2.0, "both prior POSTs must have been recorded"


def test_rejects_a_non_positive_amount(tiny_model_dir: Path) -> None:
    client = TestClient(_build_app(tiny_model_dir))

    response = client.post("/score", json=_valid_payload(amount=0))

    assert response.status_code == 422


def test_rejects_an_hour_of_day_outside_0_23(tiny_model_dir: Path) -> None:
    client = TestClient(_build_app(tiny_model_dir))

    response = client.post("/score", json=_valid_payload(hour_of_day=24))

    assert response.status_code == 422


def test_rings_reports_disabled_when_neo4j_is_not_configured(tiny_model_dir: Path) -> None:
    client = TestClient(_build_app(tiny_model_dir))

    response = client.get("/rings")

    assert response.status_code == 200
    body = response.json()
    assert body == {"enabled": False, "candidates": []}
