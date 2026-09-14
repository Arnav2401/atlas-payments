from __future__ import annotations

from pydantic import BaseModel, Field


class ScoreRequest(BaseModel):
    end_to_end_id: str
    amount: float = Field(gt=0)
    is_cash_out: bool
    debtor_account: str
    creditor_account: str
    debtor_balance_before: float = Field(ge=0)
    creditor_balance_before: float = Field(ge=0)
    hour_of_day: int = Field(ge=0, le=23)


class FeatureContributionResponse(BaseModel):
    feature: str
    value: float | None
    shap_contribution: float


class ScoreResponse(BaseModel):
    end_to_end_id: str
    probability: float
    flagged: bool
    threshold: float
    top_features: list[FeatureContributionResponse]
    source: str = "MODEL"


class RingCandidateResponse(BaseModel):
    account_id: str
    community: int
    in_degree: int
    temporal_spread_hours: int
    suspicion_score: float
    planted: bool


class RingsResponse(BaseModel):
    enabled: bool
    candidates: list[RingCandidateResponse]
