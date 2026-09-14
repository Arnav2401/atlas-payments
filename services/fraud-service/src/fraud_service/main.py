from __future__ import annotations

import asyncio
import logging
from contextlib import asynccontextmanager

import redis
from fastapi import FastAPI

from fraud_service.api.routes import router
from fraud_service.config import settings
from fraud_service.features.engineering import FeatureEngineer
from fraud_service.features.history import AccountHistory
from fraud_service.features.velocity import VelocityFeatures
from fraud_service.kafka.consumer import OutboxConsumer
from fraud_service.model.predictor import FraudPredictor
from fraud_service.scoring import ScoringService

logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    # Loaded once at process startup, not per-request — see the predictor's
    # own docstring for why (disk + tree-parsing cost has no business being
    # inside the 20ms feature-latency budget).
    predictor = FraudPredictor(settings.model_dir)

    redis_client = redis.from_url(settings.redis_url, decode_responses=True)
    feature_engineer = FeatureEngineer(velocity=VelocityFeatures(redis_client), history=AccountHistory(redis_client))

    # One ScoringService, shared by the HTTP route (api/routes.py) and the
    # Kafka consumer below — see scoring.py's module docstring for why that
    # sharing is the point, not an implementation convenience.
    scoring_service = ScoringService(engineer=feature_engineer, predictor=predictor)
    app.state.scoring_service = scoring_service

    # The Kafka consumer is optional at startup, deliberately: a developer
    # running only `uv run uvicorn ...` to exercise the synchronous /score
    # endpoint (M3's path) should not be forced to also stand up Kafka. If
    # ATLAS_KAFKA_BOOTSTRAP_SERVERS is unset, the async path (M4) simply does
    # not start, and the service logs that plainly rather than failing to boot
    # or silently pretending the async pipeline is running when it is not.
    consumer_task: asyncio.Task | None = None
    outbox_consumer: OutboxConsumer | None = None
    if settings.kafka_bootstrap_servers:
        outbox_consumer = OutboxConsumer(settings.kafka_bootstrap_servers, scoring_service)
        await outbox_consumer.start()
        consumer_task = asyncio.create_task(outbox_consumer.run())
        logger.info("Kafka consumer started against %s", settings.kafka_bootstrap_servers)
    else:
        logger.warning("ATLAS_KAFKA_BOOTSTRAP_SERVERS not set - the async (payments.submitted) path is NOT running; "
                        "only the synchronous /score endpoint is available")

    yield

    if outbox_consumer is not None:
        await outbox_consumer.stop()
    if consumer_task is not None:
        consumer_task.cancel()
        try:
            await consumer_task
        except (asyncio.CancelledError, Exception):
            pass


app = FastAPI(title="atlas-payments fraud service", lifespan=lifespan)
app.include_router(router)
