"""The one implementation of "score a payment", shared by the synchronous
HTTP path (api/routes.py, M3) and the asynchronous Kafka path
(kafka/consumer.py, M4).

This file exists because the two call sites would otherwise duplicate the
feature-timing, scoring, and record-after-score sequence — and a duplicated
sequence is exactly how the two paths would eventually disagree about
something as easy to get wrong twice as "record before or after scoring"
(see FeatureEngineer's own docstring for why that ordering is not cosmetic).
One implementation, two callers, is the same shape as feature_spec.py sharing
names between training and serving.
"""

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
        # Timed and logged separately from whatever transport wraps this call
        # (HTTP request, Kafka message) — the 20ms budget is specifically
        # feature computation, the Redis round trips, not model inference or
        # anything transport-level around it.
        feature_start = time.perf_counter()
        features = self._engineer.compute(event)
        feature_latency_ms = (time.perf_counter() - feature_start) * 1000
        logger.info("feature_computation_ms=%.3f correlation_id=%s", feature_latency_ms, correlation_id)

        result = self._predictor.score(features)

        # Record AFTER scoring, not before — see engineering.py's module
        # docstring: a payment must never see itself in its own velocity count.
        self._engineer.record(event)

        return ScoringResult(
            probability=result.probability,
            flagged=result.flagged,
            threshold=result.threshold,
            top_features=result.top_features,
            feature_latency_ms=feature_latency_ms,
        )
