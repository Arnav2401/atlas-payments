from __future__ import annotations

import json
import logging

from aiokafka import AIOKafkaConsumer, AIOKafkaProducer
from aiokafka.admin import AIOKafkaAdminClient, NewTopic
from aiokafka.errors import TopicAlreadyExistsError
from pydantic import ValidationError

from fraud_service.features.engineering import PaymentEvent
from fraud_service.kafka.events import FeatureContributionEvent, PaymentDecisionedEvent, PaymentSubmittedEvent
from fraud_service.scoring import ScoringService

logger = logging.getLogger(__name__)

SUBMITTED_TOPIC = "payments.submitted"
DECISIONED_TOPIC = "payments.decisioned"
DLQ_TOPIC = "payments.dlq"
CONSUMER_GROUP_ID = "fraud-service"


class OutboxConsumer:
    def __init__(self, bootstrap_servers: str, scoring_service: ScoringService) -> None:
        self._bootstrap_servers = bootstrap_servers
        self._scoring_service = scoring_service
        self._consumer: AIOKafkaConsumer | None = None
        self._producer: AIOKafkaProducer | None = None
        self._running = False

    async def start(self) -> None:
        await self._ensure_topics_exist()

        self._consumer = AIOKafkaConsumer(
            SUBMITTED_TOPIC,
            bootstrap_servers=self._bootstrap_servers,
            group_id=CONSUMER_GROUP_ID,
            auto_offset_reset="earliest",
            enable_auto_commit=False,
        )
        self._producer = AIOKafkaProducer(
            bootstrap_servers=self._bootstrap_servers,
            acks="all",
        )
        await self._consumer.start()
        await self._producer.start()
        self._running = True
        logger.info("OutboxConsumer started, subscribed to %s", SUBMITTED_TOPIC)

    async def _ensure_topics_exist(self) -> None:
        admin = AIOKafkaAdminClient(bootstrap_servers=self._bootstrap_servers)
        await admin.start()
        try:
            for topic in (SUBMITTED_TOPIC, DECISIONED_TOPIC, DLQ_TOPIC):
                try:
                    await admin.create_topics([NewTopic(name=topic, num_partitions=3, replication_factor=1)])
                except TopicAlreadyExistsError:
                    pass
        finally:
            await admin.close()

    async def stop(self) -> None:
        self._running = False
        if self._consumer is not None:
            await self._consumer.stop()
        if self._producer is not None:
            await self._producer.stop()

    async def run(self) -> None:
        assert self._consumer is not None and self._producer is not None
        try:
            async for record in self._consumer:
                await self._handle_one(record)
        except Exception:
            if self._running:
                raise
            logger.info("consume loop exiting after stop() was called")

    async def _handle_one(self, record) -> None:
        try:
            event = self._parse(record.value)
        except (json.JSONDecodeError, ValidationError) as poison:
            logger.warning("poison message on %s offset=%s: %s", SUBMITTED_TOPIC, record.offset, poison)
            await self._to_dlq(record, reason=str(poison))
            await self._consumer.commit()
            return

        try:
            decision = self._score(event)
        except Exception as processing_error:  # noqa: BLE001 - one bad message must not kill the consumer
            logger.exception("processing failed for payment_id=%s: %s", event.payment_id, processing_error)
            await self._to_dlq(record, reason=str(processing_error))
            await self._consumer.commit()
            return

        await self._producer.send_and_wait(
            DECISIONED_TOPIC, key=event.payment_id.encode(), value=decision.model_dump_json().encode()
        )
        await self._consumer.commit()

    @staticmethod
    def _parse(raw: bytes) -> PaymentSubmittedEvent:
        return PaymentSubmittedEvent.model_validate_json(raw)

    def _score(self, event: PaymentSubmittedEvent) -> PaymentDecisionedEvent:
        payment_event = PaymentEvent(
            amount=event.amount,
            hour_of_day=event.hour_of_day,
            is_cash_out=event.is_cash_out,
            debtor_account=event.debtor_account,
            creditor_account=event.creditor_account,
            debtor_balance_before=event.debtor_balance_before,
            creditor_balance_before=event.creditor_balance_before,
        )
        result = self._scoring_service.score(payment_event, correlation_id=event.end_to_end_id)

        return PaymentDecisionedEvent(
            payment_id=event.payment_id,
            probability=result.probability,
            flagged=result.flagged,
            threshold=result.threshold,
            top_features=[
                FeatureContributionEvent(feature=f.feature, value=f.value, shap_contribution=f.shap_contribution)
                for f in result.top_features
            ],
        )

    async def _to_dlq(self, record, reason: str) -> None:
        await self._producer.send_and_wait(
            DLQ_TOPIC,
            key=record.key,
            value=record.value,
            headers=[
                ("x-original-topic", SUBMITTED_TOPIC.encode()),
                ("x-original-offset", str(record.offset).encode()),
                ("x-failure-reason", reason.encode()[:1000]),
            ],
        )
