-- The transactional outbox, and where its downstream decision lands.
--
-- The dual-write problem this exists to fix: committing the payment to
-- Postgres and publishing to Kafka are two independent operations against two
-- independent systems. If the process dies between them, the payment exists
-- in the ledger and nothing downstream — not fraud scoring, not anything —
-- ever saw it. Money moved and nobody screened it.
--
-- The fix is this table. `outbox` is written by the SAME database transaction
-- that posts the payment (see LedgerWriter.write), so the event's existence
-- is exactly as durable as the ledger entry it describes — there is no window
-- where one exists without the other, because they are the same COMMIT. A
-- separate poller reads undispatched rows and publishes them to Kafka,
-- marking them dispatched. Atomicity where it is needed (the write), asynchrony
-- where it is wanted (the publish).
CREATE TABLE outbox (
    id             BIGSERIAL,
    aggregate_id   UUID        NOT NULL,
    topic          TEXT        NOT NULL,
    payload        JSONB       NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- NULL until the poller successfully publishes this row. Never rewritten
    -- back to NULL - a row transitions exactly once, undispatched -> dispatched.
    dispatched_at  TIMESTAMPTZ,

    CONSTRAINT outbox_pkey PRIMARY KEY (id)
);

-- The poller's own query is "give me the oldest undispatched rows". A partial
-- index over exactly that predicate stays small forever in steady state
-- (dispatched rows, which accumulate without bound, are never in it) rather
-- than scanning or indexing the whole growing table on every poll.
CREATE INDEX outbox_undispatched_idx ON outbox (created_at) WHERE dispatched_at IS NULL;

-- Where the fraud service's asynchronous verdict lands, once the decision
-- consumer reads it off payments.decisioned.
--
-- UNIQUE(payment_id) is the idempotency mechanism, and it exists because
-- Kafka delivery here is at-least-once, not exactly-once: a consumer that
-- crashes after processing a message but before committing its offset will
-- see that message again after a rebalance. Attempting an insert and letting
-- this constraint reject the duplicate is the same pattern M2 uses for the
-- idempotency key on payment_submissions - attempt, don't check-then-insert,
-- and let the database decide.
CREATE TABLE payment_decisions (
    id           BIGSERIAL,
    payment_id   UUID             NOT NULL,
    source       TEXT             NOT NULL,
    flagged      BOOLEAN          NOT NULL,
    probability  DOUBLE PRECISION,
    decided_at   TIMESTAMPTZ      NOT NULL DEFAULT now(),
    raw_payload  JSONB            NOT NULL,

    CONSTRAINT payment_decisions_pkey PRIMARY KEY (id),
    CONSTRAINT payment_decisions_payment_id_key UNIQUE (payment_id)
);
