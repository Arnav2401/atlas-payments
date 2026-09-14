from __future__ import annotations

from pydantic import BaseModel, Field


class ScoreRequest(BaseModel):
    """The Java payment API's own field names (endToEndId, debtorAccount, ...),
    not PaySim's column names. PaySim's schema shaped the training data;
    this service's wire contract is shaped by its actual caller.

    `debtor_balance_before` / `creditor_balance_before` are the ledger's own
    pre-transaction balances — the same values LedgerWriter reads for the M2
    funds check — not something this service infers. Passing them explicitly
    means fraud scoring and the funds check see the identical balance state
    for the same payment, rather than two components independently guessing
    at "the balance" from data that could disagree.
    """

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
