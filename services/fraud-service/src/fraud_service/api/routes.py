from __future__ import annotations

import logging
import time

from fastapi import APIRouter, Request

from fraud_service.api.schemas import FeatureContributionResponse, ScoreRequest, ScoreResponse
from fraud_service.features.engineering import FeatureEngineer, PaymentEvent

router = APIRouter()
logger = logging.getLogger(__name__)


@router.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


@router.post("/score", response_model=ScoreResponse)
def score(payload: ScoreRequest, request: Request) -> ScoreResponse:
    engineer: FeatureEngineer = request.app.state.feature_engineer
    predictor = request.app.state.predictor

    event = PaymentEvent(
        amount=payload.amount,
        hour_of_day=payload.hour_of_day,
        is_cash_out=payload.is_cash_out,
        debtor_account=payload.debtor_account,
        creditor_account=payload.creditor_account,
        debtor_balance_before=payload.debtor_balance_before,
        creditor_balance_before=payload.creditor_balance_before,
    )

    # Timed and logged separately from the full request, because the brief's
    # 20ms budget is specifically for feature computation — the Redis round
    # trips — not for model inference or the HTTP stack around it. Folding
    # them into one number would hide a regression in either half behind an
    # improvement in the other.
    feature_start = time.perf_counter()
    features = engineer.compute(event)
    feature_latency_ms = (time.perf_counter() - feature_start) * 1000
    logger.info("feature_computation_ms=%.3f end_to_end_id=%s", feature_latency_ms, payload.end_to_end_id)

    result = predictor.score(features)

    # Record AFTER scoring, not before — see engineering.py's module docstring.
    # A payment must never be able to see itself in its own velocity count.
    engineer.record(event)

    return ScoreResponse(
        end_to_end_id=payload.end_to_end_id,
        probability=result.probability,
        flagged=result.flagged,
        threshold=result.threshold,
        top_features=[
            FeatureContributionResponse(
                feature=f.feature, value=f.value, shap_contribution=f.shap_contribution
            )
            for f in result.top_features
        ],
    )
