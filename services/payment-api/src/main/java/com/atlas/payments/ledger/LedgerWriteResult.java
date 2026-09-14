package com.atlas.payments.ledger;

/**
 * What {@link LedgerWriter#write} hands back: the persisted submission, plus
 * the balance state the payment was decided against.
 *
 * <p>The two balances exist for one consumer: fraud scoring, which needs the
 * state a payment was evaluated relative to, not the state after it already
 * landed. They are deliberately captured once inside the same transaction
 * that posts the payment — see {@link LedgerWriter#write} — rather than
 * queried again afterward, which could race against a second payment to the
 * same account and read a different balance than the one this payment was
 * actually decided against.
 */
public record LedgerWriteResult(
        PaymentSubmissionEntity submission,
        long debtorBalanceBeforeMinor,
        long creditorBalanceBeforeMinor
) {}
