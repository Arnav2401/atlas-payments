from __future__ import annotations

import logging
import time
from dataclasses import dataclass

from fraud_service.features.engineering import FeatureEngineer, PaymentEvent
from fraud_service.model.predictor import FeatureContribution, FraudPredictor

logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class ScoringResult:
    probability: float
    flagged: bool
    threshold: float
    top_features: list[FeatureContribution]
    feature_latency_ms: float


class ScoringService:
    def __init__(self, engineer: FeatureEngineer, predictor: FraudPredictor) -> None:
        self._engineer = engineer
        self._predictor = predictor

    def score(self, event: PaymentEvent, correlation_id: str) -> ScoringResult:
        feature_start = time.perf_counter()
        features = self._engineer.compute(event)
        feature_latency_ms = (time.perf_counter() - feature_start) * 1000
        logger.info("feature_computation_ms=%.3f correlation_id=%s", feature_latency_ms, correlation_id)

        result = self._predictor.score(features)

        self._engineer.record(event)

        return ScoringResult(
            probability=result.probability,
            flagged=result.flagged,
            threshold=result.threshold,
            top_features=result.top_features,
            feature_latency_ms=feature_latency_ms,
        )
