"""Wire schemas for the two topics this service touches. Field names mirror
the Java side exactly (com.atlas.payments.outbox.PaymentSubmittedEvent /
com.atlas.payments.fraud.PaymentDecisionedEvent) — same publisher-and-consumer
pair as the HTTP schemas in api/schemas.py, same snake_case convention.
"""

from __future__ import annotations

from pydantic import BaseModel


class PaymentSubmittedEvent(BaseModel):
    payment_id: str
    end_to_end_id: str
    amount: float
    is_cash_out: bool
    debtor_account: str
    creditor_account: str
    debtor_balance_before: float
    creditor_balance_before: float
    hour_of_day: int


class FeatureContributionEvent(BaseModel):
    feature: str
    value: float | None
    shap_contribution: float


class PaymentDecisionedEvent(BaseModel):
    payment_id: str
    probability: float
    flagged: bool
    threshold: float
    top_features: list[FeatureContributionEvent]
    source: str = "MODEL"
