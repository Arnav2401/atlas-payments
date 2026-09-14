-- Account classification, so that an available-funds check has something to
-- check against.
--
-- The sign convention is worth stating plainly because it reads backwards at
-- first glance. A customer's deposit is a LIABILITY of the bank, so the customer
-- account carries a credit balance. In this ledger positive is a debit, so a
-- funded customer account has a NEGATIVE signed balance, and the money actually
-- available to them is the negation of it. Paying out debits the customer
-- account (positive) and therefore moves the balance toward zero.
--
-- SETTLEMENT accounts are the bank's own position. They are expected to run
-- negative or positive without limit and are exempt from the funds check —
-- applying a customer overdraft rule to the bank's own nostro position would be
-- meaningless.
ALTER TABLE accounts
    ADD COLUMN account_type VARCHAR(16) NOT NULL DEFAULT 'CUSTOMER';

ALTER TABLE accounts
    ADD CONSTRAINT accounts_account_type_check
    CHECK (account_type IN ('CUSTOMER', 'SETTLEMENT'));
