"""Consumes `payments.submitted`, scores each payment via the same
`ScoringService` the HTTP `/score` endpoint uses, and publishes the verdict to
`payments.decisioned`.

This is the asynchronous half of the pipeline the brief's M4 module describes:
the outbox poller (Java) durably queues an event; this loop is the "Fraud
Service consumes payments.submitted, publishes payments.decisioned" box in
the architecture diagram. It runs alongside, not instead of, the synchronous
HTTP path — see the atlas-payments README's DECISION 4 for why both exist.

## Delivery semantics, and why this loop is idempotent-safe rather than
## idempotent-guaranteeing

Kafka delivery here is at-least-once on both hops of the pipeline (outbox to
`payments.submitted`, and this consumer's publish to `payments.decisioned` —
see the Java side's OutboxPoller and PaymentDecisionConsumer javadoc for the
matching argument there). A crash after this loop publishes a decision but
before it commits its consumed offset means the SAME payment gets scored and
published again on restart. This loop does not try to prevent that — it
cannot, on its own, since offset commit and Kafka publish are not one atomic
operation any more than the ledger commit and outbox publish are on the Java
side. What makes a duplicate harmless is downstream: the Java
PaymentDecisionConsumer's unique constraint on payment_id. Re-scoring the same
payment twice is wasted work, not a correctness bug — the model is a pure
function of its inputs (modulo the velocity features' own state, which a
double-score would double-count in Redis; a known, accepted cost of this
design, not a hidden one).
"""

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
            # A first-ever startup for this group must see any backlog
            # already on the topic, not silently skip straight to new
            # messages - the same "earliest" convention application.yaml
            # uses on the Java consumer side.
            auto_offset_reset="earliest",
            # Manual commit, after this loop has actually done something with
            # a message (scored it, or routed it to the DLQ) - matching the
            # Java side's enable-auto-commit: false. Auto-commit would let the
            # broker consider a message "done" before this process had, which
            # would reopen exactly the loss window the outbox pattern exists
            # to close, just on this hop instead of the first one.
            enable_auto_commit=False,
        )
        self._producer = AIOKafkaProducer(
            bootstrap_servers=self._bootstrap_servers,
            # Mirrors the Java producer's acks=all: do not consider a publish
            # done until the broker has durably written it.
            acks="all",
        )
        await self._consumer.start()
        await self._producer.start()
        self._running = True
        logger.info("OutboxConsumer started, subscribed to %s", SUBMITTED_TOPIC)

    async def _ensure_topics_exist(self) -> None:
        """Explicit topic creation, not reliance on Kafka's own
        auto-create-on-first-produce behaviour.

        <p>Found the hard way: relying on auto-creation is a real race, not a
        theoretical one — the first publish to a not-yet-existing topic can
        genuinely fail ("Topic ... not found in cluster metadata") while the
        broker propagates the new topic's metadata, and a naive retry-free
        send can lose that first message. The Java side never has this
        problem because Spring Boot's KafkaAdmin creates every declared
        {@code NewTopic} bean at application startup, before any producer or
        consumer touches the broker — this method is the Python equivalent of
        that, done explicitly for the same reason rather than left to chance.
        """
        admin = AIOKafkaAdminClient(bootstrap_servers=self._bootstrap_servers)
        await admin.start()
        try:
            # Created one at a time, not as a single batch call: a batch
            # create_topics fails as a unit if even one topic already exists,
            # which would be the common case on every restart after the
            # first. Per-topic handling means "two of three already exist" is
            # not an error, only a genuinely failed creation is.
            for topic in (SUBMITTED_TOPIC, DECISIONED_TOPIC, DLQ_TOPIC):
                try:
                    await admin.create_topics([NewTopic(name=topic, num_partitions=3, replication_factor=1)])
                except TopicAlreadyExistsError:
                    # Another instance of this service (or the Java side, for
                    # payments.submitted) created it first - the
                    # postcondition ("the topic exists") already holds.
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
        """The consume loop. `stop()` closing the underlying consumer out from
        under this iteration is the normal aiokafka shutdown signal, not a
        polled flag — caught here and treated as a clean exit only when
        `_running` is already False (i.e. shutdown was actually requested);
        anything else propagates, since a consumer that stops itself
        unexpectedly is a real failure this process should not silently
        swallow.
        """
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
        except Exception as processing_error:  # noqa: BLE001 - deliberately broad, see module docstring
            # A processing failure that is NOT a parse failure (a bug in
            # feature engineering, Redis unreachable) is different from a
            # poison message: the message itself is well-formed, so retrying
            # it after a fix might succeed. It still goes to the DLQ rather
            # than blocking this partition forever, but the distinction is
            # worth preserving in the log for whoever investigates it.
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
