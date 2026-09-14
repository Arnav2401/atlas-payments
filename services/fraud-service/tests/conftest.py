"""A tiny, fast, synthetic model for tests — not the real trained artifact.

Tests exercise the SERVING code path (does the API wire a request through to
a score and an explanation correctly), not the real model's accuracy, which
is training/train.py's concern and is measured against the real PaySim data,
not fabricated here. A synthetic 2,000-row fixture trains in under a second
and keeps the test suite fast and independent of the 494MB dataset being
present at all.
"""

from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import pandas as pd
import pytest
import xgboost as xgb

from fraud_service.feature_spec import FEATURE_NAMES


@pytest.fixture(scope="session")
def tiny_model_dir(tmp_path_factory: pytest.TempPathFactory) -> Path:
    rng = np.random.default_rng(42)
    n = 2_000
    x = pd.DataFrame(rng.normal(size=(n, len(FEATURE_NAMES))), columns=FEATURE_NAMES)
    # A learnable, not-degenerate synthetic label so the model has real splits
    # to make and SHAP has real contributions to attribute.
    y = (x["amount"] + x["orig_balance_ratio"] * 2 - x["hour_of_day"] > 1.5).astype(int)

    model = xgb.XGBClassifier(n_estimators=20, max_depth=3, random_state=42)
    model.fit(x, y)

    model_dir = tmp_path_factory.mktemp("model")
    model.save_model(model_dir / "model.json")
    (model_dir / "metrics.json").write_text(json.dumps({"cost_based_threshold": {"threshold": 0.5}}))
    return model_dir
