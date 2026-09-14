-- The review state a flagged payment moves through — what gives the
-- analyst/supervisor role split real teeth rather than being a decoration on
-- top of two endpoints that do the same thing.
--
-- NONE:         no review is needed (never flagged, or not flagged yet).
-- UNDER_REVIEW: an analyst has requested review — the brief's "view, request
--               review" — or the payment arrived flagged and is awaiting one.
-- CLEARED / ESCALATED: a supervisor's terminal decision — the brief's
--               "clear, escalate". Terminal: neither transitions further
--               through this API once reached.
ALTER TABLE payment_decisions
    ADD COLUMN review_status VARCHAR(16) NOT NULL DEFAULT 'NONE';

ALTER TABLE payment_decisions
    ADD CONSTRAINT payment_decisions_review_status_check
    CHECK (review_status IN ('NONE', 'UNDER_REVIEW', 'CLEARED', 'ESCALATED'));

-- A flagged payment starts life needing review, unflagged ones do not - set
-- once, for rows already written before this migration ran.
UPDATE payment_decisions SET review_status = 'UNDER_REVIEW' WHERE flagged = true;
