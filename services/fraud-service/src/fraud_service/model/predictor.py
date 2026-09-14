from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path

import numpy as np
import pandas as pd
import shap
import xgboost as xgb

from fraud_service.feature_spec import FEATURE_NAMES


@dataclass(frozen=True)
class FeatureContribution:
    feature: str
    value: float | None
    shap_contribution: float


@dataclass(frozen=True)
class FraudScore:
    probability: float
    flagged: bool
    threshold: float
    top_features: list[FeatureContribution]


class FraudPredictor:
    def __init__(self, model_dir: str | Path):
        model_dir = Path(model_dir)
        self._model = xgb.XGBClassifier()
        self._model.load_model(model_dir / "model.json")
        self._explainer = shap.TreeExplainer(self._model)

        metrics = json.loads((model_dir / "metrics.json").read_text())
        self._threshold: float = metrics["cost_based_threshold"]["threshold"]

    @property
    def threshold(self) -> float:
        return self._threshold

    def score(self, features: dict[str, float | None]) -> FraudScore:
        row = pd.DataFrame(
            [[features[name] for name in FEATURE_NAMES]], columns=FEATURE_NAMES, dtype="float64"
        )

        probability = float(self._model.predict_proba(row)[0, 1])
        shap_values = self._explainer.shap_values(row)[0]

        order = np.argsort(-np.abs(shap_values))[:3]
        top_features = [
            FeatureContribution(
                feature=FEATURE_NAMES[i],
                value=None if pd.isna(row.iloc[0, i]) else float(row.iloc[0, i]),
                shap_contribution=float(shap_values[i]),
            )
            for i in order
        ]

        return FraudScore(
            probability=probability,
            flagged=probability >= self._threshold,
            threshold=self._threshold,
            top_features=top_features,
        )
