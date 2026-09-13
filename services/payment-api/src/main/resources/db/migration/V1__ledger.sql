-- Double-entry ledger.
--
-- The single most important thing about this schema is what is NOT in it:
-- there is no balance column on accounts. A balance is derived by summing an
-- account's postings. Adding to and subtracting from a stored balance is
-- simpler and faster and is the thing that makes a ledger wrong under
-- concurrency, because it turns every payment into a read-modify-write.
--
-- Money is stored as a signed integer count of the currency's minor unit, never
-- as a float and never as a decimal that has to be rounded. `amount_minor` is
-- positive for a debit and negative for a credit; the postings of one journal
-- entry therefore sum to exactly zero, and that is enforced below by the
-- database rather than trusted to the application.

CREATE TABLE accounts (
    id              BIGSERIAL,
    account_number  TEXT        NOT NULL,
    currency        VARCHAR(3)  NOT NULL,
    -- Optimistic locking. Guards mutation of the account itself; the
    -- idempotency guarantee comes from the unique constraint below, not here.
    version         BIGINT      NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT accounts_pkey PRIMARY KEY (id),
    CONSTRAINT accounts_account_number_key UNIQUE (account_number)
);

-- One journal entry per financial event. The entry is the unit of atomicity:
-- either all of its postings exist, or none do.
CREATE TABLE journal_entries (
    id            BIGSERIAL,
    external_id   UUID        NOT NULL,
    end_to_end_id TEXT        NOT NULL,
    description   TEXT        NOT NULL,
    booked_at     TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT journal_entries_pkey PRIMARY KEY (id),
    CONSTRAINT journal_entries_external_id_key UNIQUE (external_id)
);

CREATE TABLE postings (
    id               BIGSERIAL,
    journal_entry_id BIGINT  NOT NULL,
    account_id       BIGINT  NOT NULL,
    -- Signed minor units. Positive = debit, negative = credit.
    amount_minor     BIGINT  NOT NULL,
    currency         VARCHAR(3) NOT NULL,

    CONSTRAINT postings_pkey PRIMARY KEY (id),
    CONSTRAINT postings_journal_entry_fkey
        FOREIGN KEY (journal_entry_id) REFERENCES journal_entries (id),
    CONSTRAINT postings_account_fkey
        FOREIGN KEY (account_id) REFERENCES accounts (id),
    -- A zero-value posting carries no information and would let an "entry"
    -- balance trivially with one leg.
    CONSTRAINT postings_amount_nonzero CHECK (amount_minor <> 0)
);

CREATE INDEX postings_journal_entry_idx ON postings (journal_entry_id);
CREATE INDEX postings_account_idx ON postings (account_id);

-- Idempotency, enforced by the database.
--
-- This unique constraint - not application logic, not a lock, not a check-then-
-- insert - is what makes a concurrent double-submit produce exactly one ledger
-- effect. Two transactions racing on the same key: one commits, the other gets
-- a constraint violation and re-reads the winner's result. The guarantee holds
-- across processes and does not depend on the isolation level.
--
-- request_fingerprint exists so that a key reused with *different* content can
-- be rejected rather than silently returning the original payment's result.
CREATE TABLE payment_submissions (
    id                  BIGSERIAL,
    idempotency_key     TEXT        NOT NULL,
    request_fingerprint TEXT        NOT NULL,
    journal_entry_id    BIGINT      NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT payment_submissions_pkey PRIMARY KEY (id),
    CONSTRAINT payment_submissions_idempotency_key_key UNIQUE (idempotency_key),
    CONSTRAINT payment_submissions_journal_entry_fkey
        FOREIGN KEY (journal_entry_id) REFERENCES journal_entries (id)
);

-- Every journal entry must balance, and the database is what enforces it.
--
-- This has to be a DEFERRABLE CONSTRAINT TRIGGER rather than a CHECK, because
-- the invariant spans rows: after inserting the first posting the entry is
-- legitimately unbalanced, and it is only at COMMIT that being unbalanced is an
-- error. INITIALLY DEFERRED means the check runs once at commit time.
--
-- The consequence worth understanding: an application bug that writes a
-- one-legged entry cannot corrupt the ledger. It gets a failed transaction.
CREATE OR REPLACE FUNCTION assert_journal_entry_balances() RETURNS TRIGGER AS $$
DECLARE
    imbalance    BIGINT;
    leg_count    INTEGER;
    currency_cnt INTEGER;
BEGIN
    SELECT COALESCE(SUM(amount_minor), 0), COUNT(*), COUNT(DISTINCT currency)
      INTO imbalance, leg_count, currency_cnt
      FROM postings
     WHERE journal_entry_id = NEW.journal_entry_id;

    IF leg_count < 2 THEN
        RAISE EXCEPTION 'journal entry % has % posting(s); at least 2 are required',
            NEW.journal_entry_id, leg_count;
    END IF;

    -- Mixing currencies inside one entry would mean the sum-to-zero check is
    -- adding units that are not comparable. Cross-currency needs an explicit FX
    -- leg against a position account, which is out of scope.
    IF currency_cnt > 1 THEN
        RAISE EXCEPTION 'journal entry % mixes % currencies', NEW.journal_entry_id, currency_cnt;
    END IF;

    IF imbalance <> 0 THEN
        RAISE EXCEPTION 'journal entry % does not balance; postings sum to %',
            NEW.journal_entry_id, imbalance;
    END IF;

    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER postings_must_balance
    AFTER INSERT ON postings
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION assert_journal_entry_balances();
