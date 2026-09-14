from __future__ import annotations

import asyncio
import json
from pathlib import Path

import pytest
import pytest_asyncio
from aiokafka import AIOKafkaConsumer, AIOKafkaProducer
from testcontainers.community.kafka import KafkaContainer

from fraud_service.features.engineering import FeatureEngineer
from fraud_service.features.history import AccountHistory
from fraud_service.features.velocity import VelocityFeatures
from fraud_service.kafka.consumer import DECISIONED_TOPIC, DLQ_TOPIC, SUBMITTED_TOPIC, OutboxConsumer
from fraud_service.model.predictor import FraudPredictor
from fraud_service.scoring import ScoringService

pytestmark = pytest.mark.asyncio


@pytest.fixture(scope="module")
def kafka_container():
    with KafkaContainer() as container:
        yield container


@pytest_asyncio.fixture
async def consumer(kafka_container, tiny_model_dir: Path):
    import fakeredis

    redis_client = fakeredis.FakeStrictRedis(decode_responses=True)
    engineer = FeatureEngineer(velocity=VelocityFeatures(redis_client), history=AccountHistory(redis_client))
    scoring_service = ScoringService(engineer=engineer, predictor=FraudPredictor(tiny_model_dir))

    outbox_consumer = OutboxConsumer(kafka_container.get_bootstrap_server(), scoring_service)
    await outbox_consumer.start()
    task = asyncio.create_task(outbox_consumer.run())
    try:
        yield outbox_consumer
    finally:
        await outbox_consumer.stop()
        task.cancel()
        try:
            await task
        except (asyncio.CancelledError, Exception):
            pass


async def _produce(kafka_container, topic: str, key: bytes | None, value: bytes) -> None:
    producer = AIOKafkaProducer(bootstrap_servers=kafka_container.get_bootstrap_server())
    await producer.start()
    try:
        await producer.send_and_wait(topic, key=key, value=value)
    finally:
        await producer.stop()


async def _consume_matching(kafka_container, topic: str, group_suffix: str, key: bytes, timeout: float = 20.0):
    consumer = AIOKafkaConsumer(
        topic,
        bootstrap_servers=kafka_container.get_bootstrap_server(),
        group_id=f"test-{group_suffix}",
        auto_offset_reset="earliest",
    )
    await consumer.start()
    try:
        async def _find():
            async for record in consumer:
                if record.key == key:
                    return record

        return await asyncio.wait_for(_find(), timeout=timeout)
    finally:
        await consumer.stop()


def _submitted_payload(payment_id: str, amount: float = 100.0) -> bytes:
    return json.dumps(
        {
            "payment_id": payment_id,
            "end_to_end_id": "E2E-TEST",
            "amount": amount,
            "is_cash_out": False,
            "debtor_account": "DEBTOR-1",
            "creditor_account": "CREDITOR-1",
            "debtor_balance_before": 1000.0,
            "creditor_balance_before": 0.0,
            "hour_of_day": 12,
        }
    ).encode()


async def test_a_submitted_event_produces_a_decisioned_event(kafka_container, consumer):
    payment_id = "test-payment-happy-path"

    await _produce(kafka_container, SUBMITTED_TOPIC, key=payment_id.encode(), value=_submitted_payload(payment_id))

    record = await _consume_matching(kafka_container, DECISIONED_TOPIC, "happy-path", key=payment_id.encode())
    decision = json.loads(record.value)

    assert decision["payment_id"] == payment_id
    assert isinstance(decision["probability"], float)
    assert isinstance(decision["flagged"], bool)
    assert decision["source"] == "MODEL"


async def test_a_malformed_message_is_routed_to_the_dlq_not_dropped_or_crashed_on(kafka_container, consumer):
    await _produce(kafka_container, SUBMITTED_TOPIC, key=b"poison", value=b"{ not valid json at all")

    record = await _consume_matching(kafka_container, DLQ_TOPIC, "dlq-check", key=b"poison")

    assert record.value == b"{ not valid json at all"
    headers = dict(record.headers)
    assert headers["x-original-topic"] == SUBMITTED_TOPIC.encode()


async def test_a_valid_message_after_a_poison_one_is_still_processed(kafka_container, consumer):
    await _produce(kafka_container, SUBMITTED_TOPIC, key=b"poison-2", value=b"also not json")
    payment_id = "test-payment-after-poison"
    await _produce(kafka_container, SUBMITTED_TOPIC, key=payment_id.encode(), value=_submitted_payload(payment_id))

    record = await _consume_matching(kafka_container, DECISIONED_TOPIC, "after-poison", key=payment_id.encode())
    decision = json.loads(record.value)
    assert decision["payment_id"] == payment_id
