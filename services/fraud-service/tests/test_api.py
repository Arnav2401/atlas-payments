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
    # The route reads app.state.scoring_service (see routes.py) - the same
    # object main.py's lifespan builds and shares with the Kafka consumer.
    app.state.scoring_service = ScoringService(engineer=engineer, predictor=predictor)
    # Also exposed directly, test-only: production code never reads this -
    # it exists so tests can inspect Redis state white-box (see
    # test_a_second_call_sees_the_first_as_prior_velocity below) without
    # reaching into ScoringService's private fields.
    app.state.feature_engineer = engineer
    # Mirrors main.py's lifespan when ATLAS_NEO4J_URI is unset (see
    # config.py) - GET /rings must degrade cleanly, not KeyError, when this
    # test app (like most of this test suite) never configures Neo4j at all.
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
    """The API-level version of the ordering test in
    test_feature_consistency.py: record must happen, and must happen after
    scoring, through the real HTTP path, or repeated calls would never show
    rising velocity. Whether velocity ends up as a top-3 SHAP feature depends
    on the model, so this asserts the underlying state directly rather than
    hoping it surfaces in the response.
    """
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
    """The M6-optional analogue of the Kafka consumer's own optional-startup
    behaviour (see main.py's lifespan): most of this test suite, like most
    real deployments without M6 enabled, never configures Neo4j at all, and
    GET /rings must say so explicitly rather than error or silently lie
    about being enabled.
    """
    client = TestClient(_build_app(tiny_model_dir))

    response = client.get("/rings")

    assert response.status_code == 200
    body = response.json()
    assert body == {"enabled": False, "candidates": []}
