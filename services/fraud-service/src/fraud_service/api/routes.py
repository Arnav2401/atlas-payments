from __future__ import annotations

from fastapi import APIRouter, Request

from fraud_service.api.schemas import (
    FeatureContributionResponse,
    RingCandidateResponse,
    RingsResponse,
    ScoreRequest,
    ScoreResponse,
)
from fraud_service.features.engineering import PaymentEvent
from fraud_service.rings import top_ring_candidates
from fraud_service.scoring import ScoringService

router = APIRouter()


@router.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


@router.get("/rings", response_model=RingsResponse)
def rings(request: Request, top_k: int = 15) -> RingsResponse:
    driver = request.app.state.neo4j_driver
    if driver is None:
        return RingsResponse(enabled=False, candidates=[])

    candidates = top_ring_candidates(driver, top_k=top_k)
    return RingsResponse(
        enabled=True,
        candidates=[
            RingCandidateResponse(
                account_id=c.account_id,
                community=c.community,
                in_degree=c.in_degree,
                temporal_spread_hours=c.temporal_spread_hours,
                suspicion_score=c.suspicion_score,
                planted=c.planted,
            )
            for c in candidates
        ],
    )


@router.post("/score", response_model=ScoreResponse)
def score(payload: ScoreRequest, request: Request) -> ScoreResponse:
    scoring_service: ScoringService = request.app.state.scoring_service

    event = PaymentEvent(
        amount=payload.amount,
        hour_of_day=payload.hour_of_day,
        is_cash_out=payload.is_cash_out,
        debtor_account=payload.debtor_account,
        creditor_account=payload.creditor_account,
        debtor_balance_before=payload.debtor_balance_before,
        creditor_balance_before=payload.creditor_balance_before,
    )

    result = scoring_service.score(event, correlation_id=payload.end_to_end_id)

    return ScoreResponse(
        end_to_end_id=payload.end_to_end_id,
        probability=result.probability,
        flagged=result.flagged,
        threshold=result.threshold,
        top_features=[
            FeatureContributionResponse(feature=f.feature, value=f.value, shap_contribution=f.shap_contribution)
            for f in result.top_features
        ],
    )
