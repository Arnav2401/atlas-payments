package com.atlas.payments.ledger;

public record LedgerWriteResult(
        PaymentSubmissionEntity submission,
        long debtorBalanceBeforeMinor,
        long creditorBalanceBeforeMinor
) {}
