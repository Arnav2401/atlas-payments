from __future__ import annotations

from contextlib import asynccontextmanager

import redis
from fastapi import FastAPI

from fraud_service.api.routes import router
from fraud_service.config import settings
from fraud_service.features.engineering import FeatureEngineer
from fraud_service.features.history import AccountHistory
from fraud_service.features.velocity import VelocityFeatures
from fraud_service.model.predictor import FraudPredictor


@asynccontextmanager
async def lifespan(app: FastAPI):
    # Loaded once at process startup, not per-request: the model file and the
    # SHAP TreeExplainer built from it are both immutable after training, and
    # re-loading either on the request path would put disk and tree-parsing
    # cost inside the 20ms feature-latency budget for no reason.
    app.state.predictor = FraudPredictor(settings.model_dir)

    redis_client = redis.from_url(settings.redis_url, decode_responses=True)
    app.state.feature_engineer = FeatureEngineer(
        velocity=VelocityFeatures(redis_client), history=AccountHistory(redis_client)
    )
    yield


app = FastAPI(title="atlas-payments fraud service", lifespan=lifespan)
app.include_router(router)
