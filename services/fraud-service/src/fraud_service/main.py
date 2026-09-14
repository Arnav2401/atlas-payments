from __future__ import annotations

import asyncio
import logging
from contextlib import asynccontextmanager

import redis
from fastapi import FastAPI
from neo4j import GraphDatabase

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
    predictor = FraudPredictor(settings.model_dir)

    redis_client = redis.from_url(settings.redis_url, decode_responses=True)
    feature_engineer = FeatureEngineer(velocity=VelocityFeatures(redis_client), history=AccountHistory(redis_client))

    scoring_service = ScoringService(engineer=feature_engineer, predictor=predictor)
    app.state.scoring_service = scoring_service

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

    if settings.neo4j_uri:
        app.state.neo4j_driver = GraphDatabase.driver(
            settings.neo4j_uri, auth=(settings.neo4j_user, settings.neo4j_password)
        )
        logger.info("Neo4j driver connected against %s - GET /rings enabled", settings.neo4j_uri)
    else:
        app.state.neo4j_driver = None
        logger.warning("ATLAS_NEO4J_URI not set - GET /rings will report graph features as disabled")

    yield

    if outbox_consumer is not None:
        await outbox_consumer.stop()
    if consumer_task is not None:
        consumer_task.cancel()
        try:
            await consumer_task
        except (asyncio.CancelledError, Exception):
            pass
    if app.state.neo4j_driver is not None:
        app.state.neo4j_driver.close()


app = FastAPI(title="atlas-payments fraud service", lifespan=lifespan)
app.include_router(router)
