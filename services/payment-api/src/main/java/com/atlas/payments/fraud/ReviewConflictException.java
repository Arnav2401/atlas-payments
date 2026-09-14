package com.atlas.payments.fraud;

/**
 * A review-workflow transition was attempted on a payment that already has a
 * supervisor's terminal decision.
 *
 * <p>Deliberately its own type rather than a plain {@code IllegalStateException}
 * — that type is already used elsewhere in this codebase for genuine internal
 * invariant violations (see {@code LedgerWriter.resolveAccount}), which must
 * surface as a 500, not a 409. Catching {@code IllegalStateException} globally
 * to report this business conflict would have caught those too, misreporting
 * a real bug as a client-facing conflict and leaking an internal message
 * along with it. Same reasoning as every other dedicated exception type in
 * this codebase ({@code LedgerConflictException}, {@code
 * IdempotencyConflictException}, ...): a business outcome gets its own type,
 * precisely so a generic handler cannot catch more than it means to.
 */
public class ReviewConflictException extends RuntimeException {

    public ReviewConflictException(String message) {
        super(message);
    }
}
